import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useInactivityTimeout } from '@/hooks/useInactivityTimeout';
import { useAuthStore } from '@/store/auth.store';

const SESSION_START = new Date('2026-10-29T12:00:00.000Z');
const CHECK_INTERVAL_MS = 15_000;
const WARNING_MS = 28 * 60 * 1000;
const TIMEOUT_MS = 30 * 60 * 1000;
const INTERACTION_EVENTS = ['mousemove', 'keydown', 'click', 'touchstart'];
const LOGIN_REDIRECT = '/login?reason=session_expired';
const AGENDA_URL = 'http://localhost/agenda';

let locationStub: { href: string };
// Acción real del store, para devolverla entre tests: uno de los casos inyecta un mock.
const realUpdateActivity = useAuthStore.getState().updateActivity;

function startAuthenticatedSession(overrides: Partial<ReturnType<typeof useAuthStore.getState>> = {}) {
  useAuthStore.setState({
    accessToken: 'token-de-prueba',
    user: { id: 'usr-0001', email: 'medico@clinica.mx', role: 'MEDICO', tenantId: 'org-001' },
    isAuthenticated: true,
    lastActivity: Date.now(),
    showInactivityWarning: false,
    ...overrides,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(SESSION_START);
  localStorage.clear();
  // jsdom no permite redefinir `window.location`; Vitest sí puede sustituir el global.
  locationStub = { href: AGENDA_URL };
  vi.stubGlobal('location', locationStub);
  startAuthenticatedSession();
});

afterEach(() => {
  useAuthStore.setState({ updateActivity: realUpdateActivity });
  vi.unstubAllGlobals();
  vi.useRealTimers();
  localStorage.clear();
});

describe('useInactivityTimeout', () => {
  it('muestra la advertencia a los 28 minutos de inactividad', () => {
    renderHook(() => useInactivityTimeout());

    act(() => {
      vi.advanceTimersByTime(WARNING_MS - CHECK_INTERVAL_MS);
    });
    expect(useAuthStore.getState().showInactivityWarning).toBe(false);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);

    act(() => {
      vi.advanceTimersByTime(CHECK_INTERVAL_MS);
    });
    expect(useAuthStore.getState().showInactivityWarning).toBe(true);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(locationStub.href).toBe(AGENDA_URL);
  });

  it('cierra la sesión y redirige al login a los 30 minutos', () => {
    renderHook(() => useInactivityTimeout());

    act(() => {
      vi.advanceTimersByTime(TIMEOUT_MS);
    });

    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.showInactivityWarning).toBe(false);
    expect(locationStub.href).toBe(LOGIN_REDIRECT);
  });

  it('deja de escuchar la interacción y deja de revisar la inactividad al desmontar', () => {
    const updateActivity = vi.fn();
    startAuthenticatedSession({ updateActivity });

    const { unmount } = renderHook(() => useInactivityTimeout());

    // Control positivo: mientras está montado, la interacción registrada refresca la actividad.
    act(() => {
      vi.advanceTimersByTime(16_000);
    });
    act(() => {
      window.dispatchEvent(new Event('mousemove'));
    });
    expect(updateActivity).toHaveBeenCalledTimes(1);

    unmount();

    expect(vi.getTimerCount()).toBe(0);

    act(() => {
      vi.advanceTimersByTime(16_000);
      for (const type of INTERACTION_EVENTS) {
        window.dispatchEvent(new Event(type));
      }
    });
    expect(updateActivity).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(TIMEOUT_MS);
    });
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(locationStub.href).toBe(AGENDA_URL);
  });
});
