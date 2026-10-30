import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * Cableado de los dos orígenes (T6b): las bases salen de `VITE_API_URL` y `VITE_AUTH_URL`.
 *
 * Se recarga el módulo con el entorno simulado porque estas constantes se resuelven al importar,
 * y se comprueba que la base del emisor nunca duplique el prefijo `/auth` de sus rutas.
 */

async function cargarClientes(entorno: Record<string, string | undefined>) {
  vi.resetModules();
  for (const [clave, valor] of Object.entries(entorno)) {
    vi.stubEnv(clave, valor);
  }
  return import('@/lib/axios');
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('lib/axios · bases configuradas', () => {
  it('usa VITE_API_URL para el backend y VITE_AUTH_URL para el emisor', async () => {
    const { API_BASE_URL, AUTH_BASE_URL } = await cargarClientes({
      VITE_API_URL: 'http://backend.test/api/v1',
      VITE_AUTH_URL: 'http://emisor.test:4001',
    });

    expect(API_BASE_URL).toBe('http://backend.test/api/v1');
    expect(AUTH_BASE_URL).toBe('http://emisor.test:4001');
    // El import dinámico arrastra el grafo de Axios (y los mocks): margen para máquinas cargadas.
  }, 30_000);

  it('no duplica el prefijo /auth cuando VITE_AUTH_URL ya lo incluye', async () => {
    const { AUTH_BASE_URL } = await cargarClientes({
      VITE_AUTH_URL: 'http://emisor.test/api/v1/auth/',
    });

    // Las rutas del servicio son `/auth/login|refresh|logout`: la base queda sin ese segmento.
    expect(AUTH_BASE_URL).toBe('http://emisor.test/api/v1');
  }, 30_000);

  it('sin VITE_AUTH_URL la base queda vacía en vez de apuntar a un emisor inventado', async () => {
    const { AUTH_BASE_URL } = await cargarClientes({ VITE_AUTH_URL: undefined });

    expect(AUTH_BASE_URL).toBe('');
  }, 30_000);
});
