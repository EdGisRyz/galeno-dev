import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { api, authApi } from '@/lib/axios';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, makeToken } from '../helpers/jwt';

/**
 * Sondas durables de la sesión en memoria (ADR-011, T6b). Cubren tres hechos observables que el
 * resto de la suite solo comprueba de forma indirecta:
 *
 *  1. Ni el login ni el logout escriben en el almacenamiento del navegador.
 *  2. Tres 401 concurrentes producen un solo refresco y los reintentos llevan el Bearer nuevo;
 *     un segundo 401 de la misma petición cierra la sesión con exactamente dos golpes a la API.
 *  3. La tabla de normalización de `VITE_AUTH_URL`: cualquier forma aceptada resuelve a la base
 *     correcta y ninguna produce `/auth/auth`.
 *
 * Sin mocks del servicio: el refresco y el logout pasan de verdad por `authApi`. Toda la red se
 * sustituye con `adapter`, así que no se sale a ningún sitio.
 */

const TOKEN_VIEJO = makeToken({ ...CLAIMS, exp: 1793000000 });
const TOKEN_NUEVO = makeToken({ ...CLAIMS, exp: 1793000600 });

let apiPeticiones: { url: string; autorizacion: string }[];
let refrescos: string[];
let localStorageVacio: () => void;

function respuesta(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  return { data, status, statusText: status < 400 ? 'OK' : 'Unauthorized', headers: {}, config } as AxiosResponse;
}

function rechazar(config: InternalAxiosRequestConfig, status: number): never {
  throw new AxiosError(
    `Request failed with status code ${status}`,
    'ERR_BAD_REQUEST',
    config,
    undefined,
    respuesta(config, status, { mensaje: 'Sesión no válida' }),
  );
}

function autorizacionDe(config: InternalAxiosRequestConfig): string {
  return String(config.headers.get('Authorization') ?? '');
}

/** La API acepta solo `tokenValido`; con cualquier otro valor responde 401. */
function instalarAdaptadorApi(tokenValido: string | null): void {
  api.defaults.adapter = async (config) => {
    apiPeticiones.push({ url: String(config.url), autorizacion: autorizacionDe(config) });
    if (tokenValido && autorizacionDe(config) === `Bearer ${tokenValido}`) {
      return respuesta(config, 200, { ok: true });
    }
    return rechazar(config, 401);
  };
}

/** El emisor renueva con `token`; si es `null`, el refresco responde 401 y el logout 204. */
function instalarAdaptadorEmisor(token: string | null): void {
  authApi.defaults.adapter = async (config) => {
    const url = String(config.url);
    if (url.endsWith('/auth/refresh')) {
      refrescos.push(url);
      if (!token) return rechazar(config, 401);
      return respuesta(config, 200, { accessToken: token });
    }
    if (url.endsWith('/auth/login')) {
      return respuesta(config, 200, { accessToken: token ?? TOKEN_NUEVO });
    }
    return respuesta(config, 204, undefined);
  };
}

beforeEach(() => {
  vi.stubEnv('VITE_USE_MOCKS', 'false');
  localStorage.clear();

  vi.stubGlobal('location', { href: 'http://localhost/agenda' });

  apiPeticiones = [];
  refrescos = [];

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
  vi.unstubAllGlobals();
});

describe('sonda 1 · la sesión no toca el almacenamiento del navegador', () => {
  it('ni el login ni el logout llaman a Storage.setItem y localStorage queda vacío', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    localStorageVacio = () => expect(Object.keys(localStorage)).toEqual([]);

    instalarAdaptadorEmisor(TOKEN_NUEVO);

    await useAuthStore.getState().login('medico@clinica.mx', 'secreta');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(setItem).not.toHaveBeenCalled();
    localStorageVacio();

    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(setItem).not.toHaveBeenCalled();
    localStorageVacio();
  });
});

describe('sonda 2 · un refresco para N 401 y dos golpes para un reintento fallido', () => {
  it('tres 401 concurrentes producen un solo refresco y los reintentos llevan el Bearer nuevo', async () => {
    useAuthStore.setState({ accessToken: TOKEN_VIEJO, isAuthenticated: true });
    instalarAdaptadorApi(TOKEN_NUEVO);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    const respuestas = await Promise.all([
      api.get('/recurso-1'),
      api.get('/recurso-2'),
      api.get('/recurso-3'),
    ]);

    expect(respuestas.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(refrescos).toHaveLength(1);
    // Tres originales (con el token viejo) y tres reintentos (con el nuevo).
    expect(apiPeticiones).toHaveLength(6);
    expect(apiPeticiones.filter((p) => p.autorizacion === `Bearer ${TOKEN_VIEJO}`)).toHaveLength(3);
    expect(apiPeticiones.filter((p) => p.autorizacion === `Bearer ${TOKEN_NUEVO}`)).toHaveLength(3);
    expect(useAuthStore.getState().accessToken).toBe(TOKEN_NUEVO);
  });

  it('un segundo 401 de la misma petición cierra la sesión con exactamente dos golpes a la API', async () => {
    useAuthStore.setState({ accessToken: TOKEN_VIEJO, isAuthenticated: true });
    // El token nuevo tampoco sirve: el reintento vuelve a responder 401.
    instalarAdaptadorApi(null);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    await expect(api.get('/recurso')).rejects.toThrow();

    expect(apiPeticiones).toHaveLength(2);
    expect(refrescos).toHaveLength(1);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(window.location.href).toBe('/login?reason=session_expired');
  });
});

describe('sonda 3 · normalización de VITE_AUTH_URL', () => {
  async function cargarClientes(valor: string | undefined) {
    vi.resetModules();
    vi.stubEnv('VITE_AUTH_URL', valor);
    const modulo = await import('@/lib/axios');
    return modulo.AUTH_BASE_URL as string;
  }

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  const TABLA: { configurado: string | undefined; base: string }[] = [
    { configurado: 'http://localhost:4001', base: 'http://localhost:4001' },
    { configurado: 'http://localhost:4001/', base: 'http://localhost:4001' },
    { configurado: 'http://localhost:4001/auth', base: 'http://localhost:4001' },
    { configurado: 'http://localhost:4001/auth/', base: 'http://localhost:4001' },
    { configurado: 'http://localhost:4001//', base: 'http://localhost:4001' },
    { configurado: 'http://localhost:4001/api/v1/auth', base: 'http://localhost:4001/api/v1' },
    { configurado: '/api/v1/auth', base: '/api/v1' },
    { configurado: undefined, base: '' },
  ];

  for (const { configurado, base } of TABLA) {
    it(`resuelve "${String(configurado)}" a "${base}" y nunca duplica /auth`, async () => {
      const AUTH_BASE_URL = await cargarClientes(configurado);

      expect(AUTH_BASE_URL).toBe(base);
      expect(`${AUTH_BASE_URL}/auth/login`).not.toContain('/auth/auth');
    }, 30_000);
  }

  it('conserva el host cuando la URL no tiene ruta (antes quedaba en "http:/")', async () => {
    const AUTH_BASE_URL = await cargarClientes('http://auth');

    expect(AUTH_BASE_URL).toBe('http://auth');
    expect(AUTH_BASE_URL).not.toBe('http:/');
  }, 30_000);

  it('falla con un error explícito cuando el valor no es una URL utilizable', async () => {
    await expect(cargarClientes('localhost:4001')).rejects.toThrow(/VITE_AUTH_URL.*localhost:4001/);
  }, 30_000);
});
