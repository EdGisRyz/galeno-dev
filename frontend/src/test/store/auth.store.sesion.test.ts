import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, USUARIO, makeToken } from '../helpers/jwt';

/**
 * Contratos de la sesión en memoria (ADR-011, T6b):
 *  - el token y el usuario viven solo en memoria: **nada** se escribe en `localStorage`;
 *  - el login falla cerrado cuando el emisor rechaza o el token no se puede decodificar;
 *  - el refresco de arranque autentica sin persistir y no entra en bucle si falla;
 *  - cerrar sesión limpia la memoria y avisa al emisor.
 *
 * El servicio se mockea en su frontera: ningún test toca la red.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

const TOKEN = makeToken(CLAIMS);

/** Claves realmente presentes en `localStorage`: la sesión no debe crear ninguna. */
function llavesPersistidas(): string[] {
  return Object.keys(localStorage);
}

beforeEach(() => {
  localStorage.clear();
  servicio.login.mockReset();
  servicio.refresh.mockReset();
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

afterEach(() => {
  localStorage.clear();
});

describe('auth.store · login con la sesión en memoria', () => {
  it('guarda el token y el usuario en memoria y no escribe nada en localStorage', async () => {
    servicio.login.mockResolvedValue({
      accessToken: TOKEN,
      user: { sub: CLAIMS.sub, email: CLAIMS.email, role: 'MEDICO', tenantId: CLAIMS.tenantId },
    });

    const usuario = await useAuthStore.getState().login('medico@clinica.mx', 'secreta');

    expect(servicio.login).toHaveBeenCalledWith({
      email: 'medico@clinica.mx',
      password: 'secreta',
    });
    expect(usuario).toEqual(USUARIO);

    const estado = useAuthStore.getState();
    expect(estado.accessToken).toBe(TOKEN);
    expect(estado.user).toEqual(USUARIO);
    expect(estado.isAuthenticated).toBe(true);
    expect(estado.inicializando).toBe(false);
    expect(llavesPersistidas()).toEqual([]);
  });

  it('un login rechazado por el emisor deja la sesión cerrada y propaga el error', async () => {
    servicio.login.mockRejectedValue(new Error('Credenciales inválidas'));

    await expect(useAuthStore.getState().login('medico@clinica.mx', 'incorrecta')).rejects.toThrow(
      'Credenciales inválidas',
    );

    const estado = useAuthStore.getState();
    expect(estado.accessToken).toBeNull();
    expect(estado.user).toBeNull();
    expect(estado.isAuthenticated).toBe(false);
    expect(llavesPersistidas()).toEqual([]);
  });

  it('un token que no se puede decodificar no abre sesión (fail-closed)', async () => {
    servicio.login.mockResolvedValue({ accessToken: 'no-es-un-jwt' });

    await expect(useAuthStore.getState().login('medico@clinica.mx', 'secreta')).rejects.toThrow();

    const estado = useAuthStore.getState();
    expect(estado.accessToken).toBeNull();
    expect(estado.user).toBeNull();
    expect(estado.isAuthenticated).toBe(false);
    expect(llavesPersistidas()).toEqual([]);
  });
});

describe('auth.store · refresco silencioso de arranque', () => {
  it('autentica con la cookie sin persistir nada', async () => {
    servicio.refresh.mockResolvedValue({ accessToken: TOKEN });

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBe(TOKEN);
    const estado = useAuthStore.getState();
    expect(estado.isAuthenticated).toBe(true);
    expect(estado.user).toEqual(USUARIO);
    expect(estado.inicializando).toBe(false);
    expect(llavesPersistidas()).toEqual([]);
  });

  it('sin cookie válida queda anónimo, deja de inicializar y no reintenta', async () => {
    servicio.refresh.mockRejectedValue(new Error('401'));

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBeNull();
    expect(servicio.refresh).toHaveBeenCalledTimes(1);
    const estado = useAuthStore.getState();
    expect(estado.isAuthenticated).toBe(false);
    expect(estado.accessToken).toBeNull();
    expect(estado.inicializando).toBe(false);
  });

  it('cinco refrescos concurrentes producen una sola llamada al emisor', async () => {
    servicio.refresh.mockResolvedValue({ accessToken: TOKEN });

    const resultados = await Promise.all([
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
      useAuthStore.getState().refresh(),
    ]);

    expect(resultados).toEqual([TOKEN, TOKEN, TOKEN, TOKEN, TOKEN]);
    expect(servicio.refresh).toHaveBeenCalledTimes(1);
  });
});

describe('auth.store · logout', () => {
  it('limpia la memoria y avisa al emisor', async () => {
    servicio.login.mockResolvedValue({ accessToken: TOKEN });
    servicio.logout.mockResolvedValue(undefined);
    await useAuthStore.getState().login('medico@clinica.mx', 'secreta');

    await useAuthStore.getState().logout();

    expect(servicio.logout).toHaveBeenCalledTimes(1);
    const estado = useAuthStore.getState();
    expect(estado.accessToken).toBeNull();
    expect(estado.user).toBeNull();
    expect(estado.isAuthenticated).toBe(false);
    expect(llavesPersistidas()).toEqual([]);
  });

  it('cierra la sesión local aunque el emisor no responda', async () => {
    servicio.login.mockResolvedValue({ accessToken: TOKEN });
    servicio.logout.mockRejectedValue(new Error('sin red'));
    await useAuthStore.getState().login('medico@clinica.mx', 'secreta');

    await expect(useAuthStore.getState().logout()).resolves.toBeUndefined();

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
