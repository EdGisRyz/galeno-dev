import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { organizacionService } from '@/features/organizaciones/organizacion.service';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, expVigente, makeToken } from '../helpers/jwt';

/**
 * S1, S2 y S5 · el nombre de la organización en el estado de la sesión.
 *
 * El nombre es un dato de **presentación**: no viaja en el token (L2) y se consulta al backend
 * una sola vez por sesión, después de tener el token. Si la consulta falla —por ejemplo, un
 * PACIENTE que no tiene la celda y recibe 403— la sesión sigue abierta y la cabecera cae al
 * identificador del tenant.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/features/organizaciones/organizacion.service', () => ({
  organizacionService: {
    obtenerPorId: vi.fn(),
  },
}));

const servicioAuth = vi.mocked(authService);
const servicioOrganizacion = vi.mocked(organizacionService);

const ORGANIZACION = {
  id: 'org-001',
  nombre: 'Clínica Médica San Rafael',
  plan: 'CLINICA' as const,
  activo: true,
  createdAt: '2026-01-01T00:00:00.000Z',
};

const TOKEN = makeToken(CLAIMS);

beforeEach(() => {
  localStorage.clear();
  servicioAuth.login.mockReset();
  servicioAuth.refresh.mockReset();
  servicioAuth.logout.mockReset();
  servicioAuth.login.mockResolvedValue({ accessToken: TOKEN });
  servicioOrganizacion.obtenerPorId.mockReset();
  servicioOrganizacion.obtenerPorId.mockResolvedValue(ORGANIZACION);

  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: false,
    tenantSlug: '',
    organizacionNombre: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('auth.store · nombre de la organización (S1, S2)', () => {
  it('consulta la organización del tenant al iniciar sesión y guarda su nombre', async () => {
    await useAuthStore.getState().login(CLAIMS.email, 'secreta');

    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(ORGANIZACION.nombre),
    );
    expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledTimes(1);
    expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledWith(CLAIMS.tenantId);
  });

  it('una rotación de token de la misma sesión no repite la consulta', async () => {
    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(ORGANIZACION.nombre),
    );
    servicioOrganizacion.obtenerPorId.mockClear();

    servicioAuth.refresh.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, exp: expVigente(1800) }),
    });
    await useAuthStore.getState().refresh();

    expect(servicioOrganizacion.obtenerPorId).not.toHaveBeenCalled();
    expect(useAuthStore.getState().organizacionNombre).toBe(ORGANIZACION.nombre);
  });

  it('el refresco de arranque consulta una sola vez y no por cada consumidor', async () => {
    servicioAuth.refresh.mockResolvedValue({ accessToken: TOKEN });

    await Promise.all([
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
    ]);

    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(ORGANIZACION.nombre),
    );
    expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledTimes(1);
    expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledWith(CLAIMS.tenantId);
  });
});

describe('auth.store · la consulta fallida no rompe la sesión (S5)', () => {
  it('sin nombre disponible la sesión sigue abierta y conserva el identificador del tenant', async () => {
    servicioOrganizacion.obtenerPorId.mockRejectedValue(new Error('403'));

    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledTimes(1),
    );
    await Promise.resolve();

    const estado = useAuthStore.getState();
    expect(estado.isAuthenticated).toBe(true);
    expect(estado.accessToken).toBe(TOKEN);
    expect(estado.tenantSlug).toBe(CLAIMS.tenantId);
    expect(estado.organizacionNombre).toBe('');
  });

  it('un PACIENTE sin la celda de organización arranca igual y deja el nombre vacío', async () => {
    const tokenPaciente = makeToken({ ...CLAIMS, role: 'PACIENTE', medicoId: undefined });
    servicioAuth.login.mockResolvedValue({ accessToken: tokenPaciente });
    servicioOrganizacion.obtenerPorId.mockRejectedValue(new Error('403'));

    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledTimes(1),
    );
    await Promise.resolve();

    const estado = useAuthStore.getState();
    expect(estado.isAuthenticated).toBe(true);
    expect(estado.user?.role).toBe('PACIENTE');
    expect(estado.tenantSlug).toBe(CLAIMS.tenantId);
    expect(estado.organizacionNombre).toBe('');
  });

  it('cerrar sesión olvida el nombre para que no sobreviva al siguiente inicio', async () => {
    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(ORGANIZACION.nombre),
    );

    await useAuthStore.getState().logout();

    expect(useAuthStore.getState().organizacionNombre).toBe('');
    expect(useAuthStore.getState().tenantSlug).toBe('');
  });
});
