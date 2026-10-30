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
 */

const TOKEN = makeToken(CLAIMS);

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
