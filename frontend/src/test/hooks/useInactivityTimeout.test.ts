import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useInactivityTimeout } from '@/hooks/useInactivityTimeout';
import { useAuthStore } from '@/store/auth.store';
import { authService } from '@/features/auth/auth.service';

/**
 * Adaptado en T6b (ADR-011): `logout` ahora avisa al emisor (best-effort). Se mockea el servicio
 * para que el cierre de sesión por inactividad no intente salir a la red: lo que se prueba aquí
 * es el temporizador y la limpieza local, no el contrato del emisor.
 */
vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

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
const realLogout = useAuthStore.getState().logout;

function startAuthenticatedSession(overrides: Partial<ReturnType<typeof useAuthStore.getState>> = {}) {
  useAuthStore.setState({
    accessToken: 'token-de-prueba',
    user: { id: 'usr-0001', email: 'medico@clinica.mx', role: 'MEDICO', tenantId: 'org-001' },
    isAuthenticated: true,
    inicializando: false,
    lastActivity: Date.now(),
    showInactivityWarning: false,
    ...overrides,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(SESSION_START);
  localStorage.clear();
  vi.mocked(authService.logout).mockResolvedValue(undefined);
  // jsdom no permite redefinir `window.location`; Vitest sí puede sustituir el global.
  locationStub = { href: AGENDA_URL };
  vi.stubGlobal('location', locationStub);
  startAuthenticatedSession();
});

afterEach(() => {
  useAuthStore.setState({ updateActivity: realUpdateActivity, logout: realLogout });
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

  it('cierra la sesión y redirige al login a los 30 minutos', async () => {
    renderHook(() => useInactivityTimeout());

    // `await act` deja drenar la promesa del cierre de sesión antes de mirar la navegación.
    await act(async () => {
      vi.advanceTimersByTime(TIMEOUT_MS);
    });

    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.showInactivityWarning).toBe(false);
    expect(locationStub.href).toBe(LOGIN_REDIRECT);
  });

  it('no navega al login hasta que el emisor termina de cerrar la sesión', async () => {
    let resolver: () => void = () => {};
    const cierreEnCurso = new Promise<void>((resolve) => {
      resolver = resolve;
    });
    const logout = vi.fn().mockReturnValue(cierreEnCurso);
    useAuthStore.setState({ logout });

    renderHook(() => useInactivityTimeout());

    await act(async () => {
      vi.advanceTimersByTime(TIMEOUT_MS);
    });

    // R-SEG-08: si se navegara primero, el navegador abortaría el POST /auth/logout y la cookie
    // httpOnly sobreviviría; el arranque volvería a autenticar en la siguiente carga.
    expect(logout).toHaveBeenCalledTimes(1);
    expect(locationStub.href).toBe(AGENDA_URL);

    await act(async () => {
      resolver();
      await cierreEnCurso;
    });

    expect(locationStub.href).toBe(LOGIN_REDIRECT);
  });

  it('navega al login aunque el emisor falle al cerrar la sesión', async () => {
    useAuthStore.setState({ logout: vi.fn().mockRejectedValue(new Error('sin red')) });

    renderHook(() => useInactivityTimeout());

    await act(async () => {
      vi.advanceTimersByTime(TIMEOUT_MS);
    });

    // Un logout fallido no puede dejar al usuario atrapado en una UI autenticada.
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
