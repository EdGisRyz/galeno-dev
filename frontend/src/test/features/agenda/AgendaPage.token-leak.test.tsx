import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { api, authApi } from '@/lib/axios';
import { AgendaPage } from '@/features/agenda/AgendaPage';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, makeToken } from '../../helpers/jwt';

/**
 * F (T6b): un `console.error(err)` con el `AxiosError` completo publica el token en la consola del
 * navegador, porque el error lleva `config.headers.Authorization`. Cualquiera que abra DevTools lo
 * ve. El registro debe ser un resumen seguro: método, ruta y estado. Nunca el error ni el config.
 *
 * El segundo bloque fija las rutas que consume la agenda: la colección de pacientes cuelga de la
 * organización de la sesión, igual que en el contrato del backend. Las demás colecciones siguen
 * planas, así que la aserción nombra las cuatro.
 */

const TOKEN = makeToken(CLAIMS);

/** Organización del claim `tenantId` de la sesión: la dueña de la colección de pacientes. */
const ORGANIZACION_ID = 'org-001';

/** Colección del contrato anidado: `GET /organizaciones/{organizacionId}/pacientes`. */
const RUTA_PACIENTES = `/organizaciones/${ORGANIZACION_ID}/pacientes`;

/** Colección plana que el backend ya no expone: la agenda no puede volver a pedirla. */
const RUTA_PACIENTES_PLANA = '/pacientes';

function respuesta(config: InternalAxiosRequestConfig, status: number): AxiosResponse {
  return {
    data: { mensaje: 'Sesión no válida' },
    status,
    statusText: 'Unauthorized',
    headers: {},
    config,
  } as AxiosResponse;
}

function rechazar401(config: InternalAxiosRequestConfig): never {
  throw new AxiosError(
    'Request failed with status code 401',
    'ERR_BAD_REQUEST',
    config,
    undefined,
    respuesta(config, 401),
  );
}

/** Texto de todo lo que llegó a `console.error`, sin depender de la forma de cada argumento. */
function textoDeLlamadas(llamadas: unknown[][]): string {
  return llamadas
    .map((args) =>
      args
        .map((arg) => {
          if (typeof arg === 'string') return arg;
          try {
            return JSON.stringify(arg);
          } catch {
            return String(arg);
          }
        })
        .join(' '),
    )
    .join(' | ');
}

let consola: string;

beforeEach(() => {
  vi.stubEnv('VITE_USE_MOCKS', 'false');
  localStorage.clear();

  vi.stubGlobal('location', { href: 'http://localhost/agenda' });

  // La API siempre responde 401 y el refresco tampoco sirve: la sesión se cierra y la agenda
  // registra el error de cada petición.
  api.defaults.adapter = async (config) => rechazar401(config);
  authApi.defaults.adapter = async (config) => rechazar401(config);

  useAuthStore.setState({
    accessToken: TOKEN,
    user: { id: 'usr-0001', email: 'medico@clinica.mx', role: 'MEDICO', tenantId: 'org-001' },
    isAuthenticated: true,
    inicializando: false,
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });

  const espia = vi.spyOn(console, 'error').mockImplementation(() => {});
  consola = '';
  espia.mock.calls.length = 0;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('AgendaPage · el token nunca llega a la consola', () => {
  it('registra un resumen sin el Bearer cuando una petición falla con 401', async () => {
    const espia = vi.mocked(console.error);

    render(<AgendaPage />);

    await waitFor(() => expect(espia).toHaveBeenCalled());

    consola = textoDeLlamadas(espia.mock.calls as unknown as unknown[][]);

    // Control positivo: sí se registró algo del fallo (el resumen), no se silenció el error.
    expect(consola).toContain('401');
    expect(consola).toMatch(/GET/i);

    // Y el token no aparece por ninguna vía: ni el string, ni la cabecera, ni el objeto de error.
    expect(consola).not.toContain(TOKEN);
    expect(consola).not.toContain('Authorization');
    expect(consola).not.toContain('Bearer');
  });
});

describe('AgendaPage · rutas del contrato del backend (colección de pacientes anidada)', () => {
  /**
   * Adaptador que anota cada ruta pedida y responde listas vacías. Se instala por prueba porque el
   * `beforeEach` deja la API rechazando todo con 401.
   */
  function espiarPeticiones(peticiones: (string | undefined)[]) {
    api.defaults.adapter = async (config) => {
      peticiones.push(config.url);
      return {
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse;
    };
  }

  it('pide el directorio en la colección de la organización de la sesión', async () => {
    const peticiones: (string | undefined)[] = [];
    espiarPeticiones(peticiones);

    render(<AgendaPage />);

    await waitFor(() => expect(peticiones).toContain(RUTA_PACIENTES));
    expect(peticiones).not.toContain(RUTA_PACIENTES_PLANA);

    // Las demás colecciones no cambian: el contrato solo anidó la de pacientes.
    expect(peticiones).toContain('/citas');
    expect(peticiones).toContain('/medicos');
    expect(peticiones).toContain('/consultorios');
  });

  it('no pide el directorio si la sesión no trae organización', async () => {
    const peticiones: (string | undefined)[] = [];
    espiarPeticiones(peticiones);

    useAuthStore.setState({
      accessToken: null,
      user: null,
      isAuthenticated: false,
      tenantSlug: '',
      inicializando: false,
    });

    render(<AgendaPage />);

    // El resto de la agenda sí carga; la colección que necesita organización no se pide.
    await waitFor(() => expect(peticiones).toContain('/consultorios'));
    expect(peticiones.some((url) => url?.includes('/pacientes'))).toBe(false);
  });
});
