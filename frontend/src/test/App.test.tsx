import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '@/App';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import type { UserRole } from '@/features/auth/auth.types';
import { CLAIMS, makeToken } from './helpers/jwt';

/**
 * T7 · cableado de roles en `App` (ADR-013).
 *
 * `/agenda` es la única pantalla protegida y hoy la comparten `ADMIN` y `MEDICO`. `PACIENTE` no
 * tiene pantalla todavía: se le manda al login con `reason=sin_acceso` en vez de dejarlo entrar a
 * una agenda clínica que la matriz de ADR-013 no le concede.
 *
 * La agenda real se sustituye por un marcador —haría red— y el emisor se mockea en su frontera:
 * el refresco de arranque sí se ejerce, porque es el que establece la sesión.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/features/agenda/AgendaPage', () => ({
  AgendaPage: () => 'agenda-clinica',
}));

const servicio = vi.mocked(authService);

/** Arranca `App` con un refresco que entrega una sesión del rol indicado. */
function montarAppConSesion(role: UserRole) {
  servicio.refresh.mockResolvedValue({ accessToken: makeToken({ ...CLAIMS, role }) });

  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  servicio.refresh.mockReset();
  servicio.login.mockReset();
  servicio.logout.mockReset();

  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: true,
    tenantSlug: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('App · /agenda según ADR-013', () => {
  it('un ADMIN llega a la agenda', async () => {
    montarAppConSesion('ADMIN');

    expect(await screen.findByText('agenda-clinica')).toBeInTheDocument();
  });

  it('un MEDICO llega a la agenda', async () => {
    montarAppConSesion('MEDICO');

    expect(await screen.findByText('agenda-clinica')).toBeInTheDocument();
  });

  it('un PACIENTE no llega a la agenda: se queda en el login', async () => {
    montarAppConSesion('PACIENTE');

    expect(await screen.findByText('Iniciar Sesión')).toBeInTheDocument();
    expect(screen.queryByText('agenda-clinica')).not.toBeInTheDocument();
  });
});
