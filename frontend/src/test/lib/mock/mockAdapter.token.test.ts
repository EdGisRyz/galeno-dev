import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '@/lib/axios';
import { mockUsers } from '@/lib/mock/mockData';
import { useAuthStore } from '@/store/auth.store';

/**
 * T7 · ADR-012: el token de acceso dura **30 minutos**, alineado con la ventana de inactividad.
 *
 * El mock fabricaba `exp` 8 h en el futuro, así que el cierre por inactividad era decorativo: la
 * sesión seguía siendo válida mucho después de que el cliente la cerrara. Aquí se comprueba el
 * contrato del token simulado: `exp` e `iat` presentes y numéricos, y una vigencia de 30 minutos
 * como máximo. La segunda prueba exige que la forma de los claims siga siendo la que el store
 * decodifica, para que el flujo de desarrollo no se rompa al endurecerse el store.
 */

/** Ventana de vigencia máxima del token de acceso (ADR-012). */
const VIGENCIA_MAXIMA_SEGUNDOS = 1800;

const usuarioDelMock = mockUsers[1];

/** Payload del token, decodificado como lo hace el store (base64url → JSON). */
function claimsDe(token: string): Record<string, unknown> {
  const segmento = token.split('.')[1];
  const base64 = segmento.replace(/-/g, '+').replace(/_/g, '/');
  const relleno = '='.repeat((4 - (base64.length % 4)) % 4);

  return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(base64 + relleno), (c) => c.charCodeAt(0))));
}

beforeEach(() => {
  vi.stubEnv('DEV', true);
  vi.stubEnv('VITE_USE_MOCKS', 'true');

  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: false,
    tenantSlug: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('mockAdapter · vigencia del token simulado (ADR-012)', () => {
  it('emite un token de 30 minutos con `exp` e `iat` numéricos', async () => {
    const { data } = await authApi.post('/auth/login', {
      email: usuarioDelMock.email,
      password: 'secreta',
    });

    const claims = claimsDe(data.accessToken);

    expect(typeof claims.exp).toBe('number');
    expect(typeof claims.iat).toBe('number');
    expect(Number.isInteger(claims.exp)).toBe(true);
    expect(Number.isInteger(claims.iat)).toBe(true);

    const vigencia = (claims.exp as number) - (claims.iat as number);
    expect(vigencia).toBeGreaterThan(0);
    expect(vigencia).toBeLessThanOrEqual(VIGENCIA_MAXIMA_SEGUNDOS);
    // Y no nace ya vencido: el mock tiene que servir para trabajar.
    expect(claims.exp as number).toBeGreaterThan(Math.floor(Date.now() / 1000));
  });

  it('conserva la forma de claims que el store decodifica: el token del mock abre sesión', async () => {
    const usuario = await useAuthStore.getState().login(usuarioDelMock.email, 'secreta');

    expect(usuario.email).toBe(usuarioDelMock.email);
    expect(usuario.role).toBe(usuarioDelMock.rol);
    expect(usuario.tenantId).toBe(usuarioDelMock.organizacion_id);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });
});
