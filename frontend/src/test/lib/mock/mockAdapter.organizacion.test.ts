import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/axios';
import { mockOrganization, mockUsers } from '@/lib/mock/mockData';
import { useAuthStore } from '@/store/auth.store';

/**
 * S2 · el mock de `GET /organizaciones/{id}` sirve el nombre de la organización del tenant.
 *
 * La ruta es **exacta**: no puede atrapar las colecciones anidadas de la misma base
 * (`/organizaciones/{id}/consultorios`, `/pacientes`, `/médicos`, `/marca`). Un `.includes()`
 * habría devuelto el objeto de la organización donde una pantalla esperaba un arreglo.
 */

const usuarioDelMock = mockUsers[1];

beforeEach(() => {
  vi.stubEnv('DEV', true);
  vi.stubEnv('VITE_USE_MOCKS', 'true');

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

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('mockAdapter · GET /organizaciones/{id} (ruta exacta)', () => {
  it('devuelve la organización simulada con su nombre', async () => {
    const { data, status } = await api.get<{ id: string; nombre: string }>(
      `/organizaciones/${mockOrganization.id}`,
    );

    expect(status).toBe(200);
    expect(data.id).toBe(mockOrganization.id);
    expect(data.nombre).toBe(mockOrganization.nombre);
  });

  it('no atrapa las colecciones anidadas que cuelgan de la misma organización', async () => {
    const { data, status } = await api.get<unknown[]>(
      `/organizaciones/${mockOrganization.id}/consultorios`,
    );

    expect(status).toBe(200);
    expect(Array.isArray(data)).toBe(true);
  });

  it('un id que no es el de la organización simulada responde 404', async () => {
    // El adaptador propio resuelve la promesa con el status (no pasa por `settle` de Axios), igual
    // que las demás rutas 404/409 del mock. Lo que importa es que no devuelve una organización.
    const respuesta = await api.get('/organizaciones/org-inexistente');

    expect(respuesta.status).toBe(404);
  });

  it('al iniciar sesión, el estado guarda el nombre del tenant de la sesión', async () => {
    await useAuthStore.getState().login(usuarioDelMock.email, 'secreta');

    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(mockOrganization.nombre),
    );
    expect(useAuthStore.getState().tenantSlug).toBe(usuarioDelMock.organizacionId);
  });
});
