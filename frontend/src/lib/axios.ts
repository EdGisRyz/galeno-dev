// Clientes HTTP de la aplicación. Este es el ÚNICO módulo que crea instancias de Axios
// (GAL-Front001 §2, guardarraíl 1): ni componentes, ni features, ni páginas importan `axios`.
//
// El navegador habla con DOS orígenes (ADR-011):
//   - `api`     → `VITE_API_URL`, el backend. Adjunta `Authorization: Bearer <token>` leyendo el
//                 token de la memoria del cliente: nunca de `localStorage`.
//   - `authApi` → `VITE_AUTH_URL`, el emisor de tokens. **Nunca** adjunta el Bearer: se autentica
//                 solo con la cookie de refresco `httpOnly`, que el JavaScript no puede leer.
// `VITE_AUTH_URL` es una variable aparte a propósito: si el AuthService real termina siendo
// backend-a-backend, se agrega un proxy y solo cambia esa variable.

import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { setupMockAdapter } from './mock/mockAdapter';

/** Segmento que ya llevan las rutas del servicio (`/auth/login`, `/auth/refresh`, `/auth/logout`). */
const SEGMENTO_AUTH = '/auth';

/**
 * Recorta el sufijo `/auth` de una RUTA (nunca de un host: `http://auth` no puede quedar `http:/`).
 * Es idempotente: `…/auth/auth` no es una forma válida y quedaría `…/auth`, así que nunca se genera
 * `/auth/auth/…` al concatenar la ruta del servicio.
 */
function recortarSegmentoAuth(ruta: string): string {
  const sinBarrasFinales = ruta.replace(/\/+$/, '');
  return sinBarrasFinales.endsWith(SEGMENTO_AUTH)
    ? sinBarrasFinales.slice(0, -SEGMENTO_AUTH.length)
    : sinBarrasFinales;
}

/**
 * Normaliza `VITE_AUTH_URL` a la base sobre la que se piden `/auth/*`.
 *
 * Sin valor configurado devuelve `''` (mismo origen): las llamadas al emisor fallan de forma
 * visible contra el origen del frontend en lugar de apuntar a un emisor adivinado.
 */
function normalizarBaseDelEmisor(configurada: string | undefined): string {
  // Un valor que no sea texto (una variable mal tipada en el `.env`) también es un error de
  // configuración: mismo mensaje explícito en vez de un `TypeError` sobre `.trim`.
  if (configurada !== undefined && typeof configurada !== 'string') {
    throw errorDeEmisorInvalido(String(configurada));
  }

  const valor = (configurada ?? '').trim();

  if (valor === '') return '';

  // Ruta relativa al mismo origen: `/api/v1/auth` → `/api/v1`.
  if (valor.startsWith('/')) {
    return recortarSegmentoAuth(valor);
  }

  let url: URL;
  try {
    url = new URL(valor);
  } catch {
    throw errorDeEmisorInvalido(valor);
  }

  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.hostname === '') {
    throw errorDeEmisorInvalido(valor);
  }

  // El recorte se aplica solo a la ruta: si se aplicara al texto completo, `http://auth` se
  // quedaría en `http:/` y el primer login moriría con `Invalid URL`.
  url.pathname = recortarSegmentoAuth(url.pathname);
  url.search = '';
  url.hash = '';

  return url.toString().replace(/\/+$/, '');
}

function errorDeEmisorInvalido(valor: string): Error {
  return new Error(
    `VITE_AUTH_URL no es una URL utilizable: "${valor}". ` +
      'Use una ruta del mismo origen ("/api/v1/auth") o una URL http(s) con host ' +
      '("http://localhost:4001").',
  );
}

/** Base del backend. Sin `VITE_API_URL` cae al prefijo servido por el mismo origen. */
export const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/**
 * Base del emisor de tokens.
 *
 * Formas aceptadas por `normalizarBaseDelEmisor`:
 *   - relativa al mismo origen: `/api/v1/auth` → `/api/v1`;
 *   - absoluta http(s) con host: `http://localhost:4001`, `…/`, `…/auth`, `…/auth/`, `…//`,
 *     `…/api/v1/auth`;
 *   - sin configurar: base vacía (mismo origen).
 *
 * Cualquier otra forma lanza un `Error` que nombra la variable y el valor: un valor mal escrito
 * debe fallar al arrancar, no más tarde como `Invalid URL "http:/"` en el primer login.
 */
export const AUTH_BASE_URL = normalizarBaseDelEmisor(import.meta.env.VITE_AUTH_URL);

export const api = axios.create({
  baseURL: API_BASE_URL,
  // La cookie de refresco solo viaja con credenciales; el backend habilita CORS con origen
  // explícito (nunca comodín) porque con credenciales `*` no es una respuesta válida.
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const authApi = axios.create({
  baseURL: AUTH_BASE_URL,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Activar mocks si está en desarrollo o VITE_USE_MOCKS=true (fail-closed, ver mockAdapter.ts).
// Se instalan en los dos clientes para que el flujo de login siga siendo navegable sin emisor.
setupMockAdapter(api);
setupMockAdapter(authApi);

/**
 * Puente entre los clientes HTTP y la sesión en memoria.
 *
 * El store es el único dueño del token y del usuario; aquí solo se consumen. Se inyecta en vez
 * de importar el store para que las dependencias vayan en una sola dirección
 * (store → service → axios) y no exista un ciclo axios → store → service → axios.
 */
export interface PuenteSesion {
  /** Token de acceso vigente en memoria, o `null` si no hay sesión. */
  obtenerToken: () => string | null;
  /** Pide un refresco (single-flight en el store). Devuelve el token nuevo o `null` si falló. */
  refrescar: () => Promise<string | null>;
  /** Sesión irrecuperable: limpiar memoria y volver al login con el motivo explícito. */
  sesionExpirada: () => void;
}

let puente: PuenteSesion | null = null;

/** Registra la sesión en memoria. Lo llama el store una sola vez, al cargarse. */
export function conectarSesionHttp(sesion: PuenteSesion): void {
  puente = sesion;
}

/** Configuración de una petición con los datos que necesita el reintento tras un 401. */
type ConfigReintentable = InternalAxiosRequestConfig & {
  /** La petición ya se repitió una vez: no se vuelve a refrescar ni a repetir. */
  _reintento?: boolean;
  /** Token con el que se envió la petición: sirve para detectar que otro refresco ya ocurrió. */
  _tokenEnviado?: string | null;
};

// Interceptor de request: adjunta el Bearer desde la memoria del cliente.
api.interceptors.request.use((config) => {
  const token = puente?.obtenerToken() ?? null;
  const reconfigurable = config as ConfigReintentable;
  reconfigurable._tokenEnviado = token;

  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  } else {
    config.headers.delete('Authorization');
  }

  return config;
});

/** Sin puente registrado no hay sesión que cerrar; con él, se delega al store. */
function expirarSesion(): void {
  puente?.sesionExpirada();
}

/**
 * Manejo centralizado del 401 (ADR-011): refresca **una** vez, repite la petición original
 * **una** vez y, si el refresco falla o el reintento vuelve a dar 401, cierra la sesión y manda
 * al login con `reason=session_expired`.
 *
 * El refresco lo ejecuta el store, que es single-flight: N peticiones concurrentes esperan la
 * misma promesa y producen una sola llamada al emisor. Además, si al llegar el 401 el token en
 * memoria ya cambió (otra petición refrescó antes de que se atendiera este error), no se pide
 * otro refresco: basta con repetir con el token vigente.
 */
async function manejar401(error: AxiosError): Promise<unknown> {
  const config = error.config as ConfigReintentable | undefined;

  if (!config || error.response?.status !== 401 || !puente) {
    throw error;
  }

  if (config._reintento) {
    expirarSesion();
    throw error;
  }

  config._reintento = true;

  const tokenActual = puente.obtenerToken();
  if (!tokenActual || tokenActual === config._tokenEnviado) {
    const renovado = await puente.refrescar();
    if (!renovado) {
      expirarSesion();
      throw error;
    }
  }

  return api(config);
}

// `authApi` no registra manejo de 401 a propósito: el refresco y el logout se autentican por
// cookie, y un 401 del emisor es una respuesta definitiva, no un motivo para refrescar de nuevo.
// Que el interceptor viva solo en `api` es lo que hace imposible la recursión del refresco.
api.interceptors.response.use((response) => response, manejar401);
