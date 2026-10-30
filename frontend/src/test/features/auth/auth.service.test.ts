import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { InternalAxiosRequestConfig } from 'axios';
import { authApi, AUTH_BASE_URL } from '@/lib/axios';
import { authService } from '@/features/auth/auth.service';

/**
 * Contrato del emisor (T6b, `tools/dev-auth/README.md`): el login canjea credenciales por un
 * token, el refresco y el logout se autentican por la cookie `httpOnly`. Ninguna llamada lleva
 * `Authorization: Bearer`: el token de acceso no se expone al emisor.
 */

interface Peticion {
  metodo: string;
  url: string;
  autorizacion: string;
  withCredentials: boolean | undefined;
  cuerpo: unknown;
}

let peticiones: Peticion[];
let responder: (config: InternalAxiosRequestConfig) => { status: number; data: unknown };

beforeEach(() => {
  vi.stubEnv('VITE_USE_MOCKS', 'false');

  peticiones = [];
  responder = () => ({ status: 200, data: {} });

  authApi.defaults.adapter = async (config) => {
    peticiones.push({
      metodo: String(config.method),
      url: String(config.url),
      autorizacion: String(config.headers.get('Authorization') ?? ''),
      withCredentials: config.withCredentials,
      cuerpo: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    });

    const { status, data } = responder(config);
    return { data, status, statusText: 'OK', headers: {}, config };
  };
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('auth.service · contrato del emisor', () => {
  it('login hace POST /auth/login con las credenciales y devuelve token y usuario', async () => {
    const respuesta = {
      accessToken: 'token-de-acceso',
      user: { sub: 'usr-0001', email: 'medico@clinica.mx', role: 'MEDICO', tenantId: 'org-001' },
    };
    responder = () => ({ status: 200, data: respuesta });

    const resultado = await authService.login({
      email: 'medico@clinica.mx',
      password: 'secreta',
    });

    expect(resultado).toEqual(respuesta);
    expect(peticiones).toHaveLength(1);
    expect(peticiones[0].metodo).toBe('post');
    expect(peticiones[0].url).toBe('/auth/login');
    expect(peticiones[0].cuerpo).toEqual({ email: 'medico@clinica.mx', password: 'secreta' });
    expect(peticiones[0].autorizacion).toBe('');
    expect(peticiones[0].withCredentials).toBe(true);
  });

  it('refresh hace POST /auth/refresh sin cuerpo y sin Bearer', async () => {
    responder = () => ({ status: 200, data: { accessToken: 'token-renovado' } });

    const resultado = await authService.refresh();

    expect(resultado).toEqual({ accessToken: 'token-renovado' });
    expect(peticiones[0].metodo).toBe('post');
    expect(peticiones[0].url).toBe('/auth/refresh');
    expect(peticiones[0].autorizacion).toBe('');
    expect(peticiones[0].withCredentials).toBe(true);
  });

  it('logout hace POST /auth/logout', async () => {
    responder = () => ({ status: 204, data: undefined });

    await expect(authService.logout()).resolves.toBeUndefined();

    expect(peticiones[0].metodo).toBe('post');
    expect(peticiones[0].url).toBe('/auth/logout');
    expect(peticiones[0].autorizacion).toBe('');
    expect(peticiones[0].withCredentials).toBe(true);
  });

  it('apunta las rutas del emisor sobre su propia base, no sobre la del backend', () => {
    expect(authApi.defaults.baseURL).toBe(AUTH_BASE_URL);
  });
});
