import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { api, authApi, API_BASE_URL, AUTH_BASE_URL } from '@/lib/axios';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, makeToken } from '../helpers/jwt';

/**
 * Contrato de los dos clientes HTTP y del manejo del 401 (ADR-011, T6b).
 *
 * Todo se ejerce sobre las instancias reales, con `adapter` propio: no hay red. El servicio de
 * auth tampoco se mockea aquí a propósito, para que el refresco pase de verdad por `authApi`
 * (cookie, sin Bearer) y se pueda contar cuántas veces se pide.
 */

const TOKEN_VIEJO = makeToken({ ...CLAIMS, exp: 1793000000 });
const TOKEN_NUEVO = makeToken({ ...CLAIMS, exp: 1793000600 });

interface Peticion {
  url: string;
  autorizacion: string;
}

let redirigido: { href: string };
let apiPeticiones: Peticion[];
let emisorPeticiones: Peticion[];
let refrescos: number;
let logouts: number;

function respuesta(config: InternalAxiosRequestConfig, status: number, data: unknown): AxiosResponse {
  return {
    data,
    status,
    statusText: status < 400 ? 'OK' : 'Unauthorized',
    headers: {},
    config,
  } as AxiosResponse;
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

/** `api` acepta solo el token indicado; con cualquier otro responde 401. `null` = siempre 401. */
function instalarAdaptadorApi(tokenValido: string | null): void {
  api.defaults.adapter = async (config) => {
    apiPeticiones.push({ url: String(config.url), autorizacion: autorizacionDe(config) });
    if (tokenValido && autorizacionDe(config) === `Bearer ${tokenValido}`) {
      return respuesta(config, 200, { ok: true });
    }
    return rechazar(config, 401);
  };
}

/** Emisor que responde el refresco con `token`, o 401 si se pide `null`. */
function instalarAdaptadorEmisor(token: string | null): void {
  authApi.defaults.adapter = async (config) => {
    const url = String(config.url);
    emisorPeticiones.push({ url, autorizacion: autorizacionDe(config) });

    if (url.endsWith('/auth/refresh')) {
      refrescos += 1;
      if (!token) return rechazar(config, 401);
      return respuesta(config, 200, { accessToken: token });
    }

    if (url.endsWith('/auth/logout')) {
      logouts += 1;
      return respuesta(config, 204, undefined);
    }

    return respuesta(config, 200, { accessToken: token ?? TOKEN_NUEVO });
  };
}

function iniciarSesionEnMemoria(accessToken: string): void {
  useAuthStore.setState({
    accessToken,
    user: { id: 'usr-0001', email: 'medico@clinica.mx', role: 'MEDICO', tenantId: 'org-001' },
    isAuthenticated: true,
    inicializando: false,
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
}

beforeEach(() => {
  // Los mocks de datos clínicos no pueden interceptar estas peticiones: aquí el adaptador manda.
  vi.stubEnv('VITE_USE_MOCKS', 'false');
  localStorage.clear();

  redirigido = { href: 'http://localhost/agenda' };
  vi.stubGlobal('location', redirigido);

  apiPeticiones = [];
  emisorPeticiones = [];
  refrescos = 0;
  logouts = 0;

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

describe('clientes HTTP · dos orígenes, un solo módulo', () => {
  it('expone dos instancias distintas, ambas con credenciales y con su base configurada', () => {
    expect(api).not.toBe(authApi);
    expect(api.defaults.baseURL).toBe(API_BASE_URL);
    expect(authApi.defaults.baseURL).toBe(AUTH_BASE_URL);
    expect(api.defaults.withCredentials).toBe(true);
    expect(authApi.defaults.withCredentials).toBe(true);
  });

  it('api envía el Bearer desde la memoria y authApi nunca lo envía', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    instalarAdaptadorApi(TOKEN_VIEJO);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    await api.get('/recurso');
    await authApi.post('/auth/refresh');

    expect(apiPeticiones).toHaveLength(1);
    expect(apiPeticiones[0].autorizacion).toBe(`Bearer ${TOKEN_VIEJO}`);
    // El refresco se autentica por cookie: si llevara el Bearer, expondría el token al emisor.
    expect(refrescos).toBe(1);
    expect(emisorPeticiones).toHaveLength(1);
    expect(emisorPeticiones[0].autorizacion).toBe('');
  });

  it('sin sesión no envía cabecera Authorization', async () => {
    instalarAdaptadorApi(TOKEN_VIEJO);

    await expect(api.get('/recurso')).rejects.toThrow();

    expect(apiPeticiones[0].autorizacion).toBe('');
  });
});

describe('manejo del 401', () => {
  it('refresca una vez y repite la petición original con el token nuevo', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    instalarAdaptadorApi(TOKEN_NUEVO);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    const respuestaFinal = await api.get('/recurso');

    expect(respuestaFinal.status).toBe(200);
    expect(refrescos).toBe(1);
    // Original + un único reintento.
    expect(apiPeticiones).toHaveLength(2);
    expect(apiPeticiones[1].autorizacion).toBe(`Bearer ${TOKEN_NUEVO}`);
    expect(useAuthStore.getState().accessToken).toBe(TOKEN_NUEVO);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('cinco 401 en paralelo producen exactamente un refresco', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    instalarAdaptadorApi(TOKEN_NUEVO);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    const respuestas = await Promise.all([
      api.get('/recurso-1'),
      api.get('/recurso-2'),
      api.get('/recurso-3'),
      api.get('/recurso-4'),
      api.get('/recurso-5'),
    ]);

    expect(respuestas.map((r) => r.status)).toEqual([200, 200, 200, 200, 200]);
    expect(refrescos).toBe(1);
    expect(apiPeticiones).toHaveLength(10);
  });

  it('un segundo 401 cierra la sesión y redirige al login con el motivo', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    // El token nuevo tampoco sirve: el reintento vuelve a dar 401.
    instalarAdaptadorApi(null);
    instalarAdaptadorEmisor(TOKEN_NUEVO);

    await expect(api.get('/recurso')).rejects.toThrow();

    expect(refrescos).toBe(1);
    expect(apiPeticiones).toHaveLength(2);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(logouts).toBe(1);
    expect(redirigido.href).toBe('/login?reason=session_expired');
  });

  it('si el refresco falla no se repite la petición: cierra sesión y redirige', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    instalarAdaptadorApi(null);
    instalarAdaptadorEmisor(null);

    await expect(api.get('/recurso')).rejects.toThrow();

    expect(refrescos).toBe(1);
    expect(apiPeticiones).toHaveLength(1);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(redirigido.href).toBe('/login?reason=session_expired');
  });

  it('el refresco del emisor no pasa por el manejo de 401 de la API (sin recursión)', async () => {
    iniciarSesionEnMemoria(TOKEN_VIEJO);
    instalarAdaptadorApi(null);
    instalarAdaptadorEmisor(null);

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBeNull();
    expect(refrescos).toBe(1);
    // Si el refresco usara `api`, un 401 del emisor habría disparado otro refresco.
    expect(apiPeticiones).toHaveLength(0);
  });
});
