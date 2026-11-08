import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosAdapter } from 'axios';
import { api, authApi } from '@/lib/axios';
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
 *
 * El segundo bloque fija las rutas de pacientes del mock contra el contrato del backend: la
 * colección cuelga de la organización dueña y el ítem es plano. Si el mock volviera a aceptar la
 * colección plana, el desarrollo local diría que la pantalla funciona cuando contra el backend
 * real responde 404.
 */

/** Ventana de vigencia máxima del token de acceso (ADR-012). */
const VIGENCIA_MAXIMA_SEGUNDOS = 1800;

/** Organización dueña de la colección de pacientes en el mock. */
const ORGANIZACION_ID = 'org-001';

/** Colección del contrato anidado: `GET/POST /organizaciones/{organizacionId}/pacientes`. */
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes`;

/** Colección plana que el backend ya no expone: el mock tampoco puede servirla. */
const RUTA_PLANA = '/pacientes';

/**
 * Respuesta inequívoca del adaptador centinela: si llega al llamador, el mock **no** interceptó
 * la petición. Se instala por petición para probar que el mock no es más permisivo que el backend
 * sin salir a la red.
 */
const SIN_MOCK = 'atendida-por-el-adaptador-centinela';

const adaptadorCentinela: AxiosAdapter = async (config) => ({
  data: SIN_MOCK,
  status: 299,
  statusText: 'Centinela',
  headers: {},
  config,
});

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

describe('mockAdapter · rutas de pacientes del contrato (colección anidada, ítem plano)', () => {
  it('sirve la lista en la colección anidada de la organización', async () => {
    const { data, status } = await api.get<{ id: string; organizacionId: string }[]>(
      RUTA_COLECCION,
    );

    expect(status).toBe(200);
    expect(data.length).toBeGreaterThan(0);
    expect(data.every((paciente) => paciente.organizacionId === ORGANIZACION_ID)).toBe(true);
  });

  it('da de alta al paciente en la organización del path y responde 201', async () => {
    const { data, status } = await api.post<{ id: string; organizacionId: string }>(
      RUTA_COLECCION,
      { nombre: 'Paciente de prueba' },
    );

    expect(status).toBe(201);
    // La organización dueña viene del path, no del cuerpo ni de un valor por defecto.
    expect(data.organizacionId).toBe(ORGANIZACION_ID);

    const lista = await api.get<{ id: string }[]>(RUTA_COLECCION);
    expect(lista.data.some((paciente) => paciente.id === data.id)).toBe(true);
  });

  it('actualiza solo en la ruta plana del paciente', async () => {
    const { data, status } = await api.put<{ id: string; nombre: string }>('/pacientes/pac-001', {
      nombre: 'Nombre Actualizado',
    });

    expect(status).toBe(200);
    expect(data.id).toBe('pac-001');
    expect(data.nombre).toBe('Nombre Actualizado');
  });

  it('no secuestra rutas que el backend no expone', async () => {
    // Colección plana: retirada del backend.
    const listaPlana = await api.get(RUTA_PLANA, { adapter: adaptadorCentinela });
    expect(listaPlana.data).toBe(SIN_MOCK);

    // Ítem anidado: el paciente se identifica solo por su ruta plana.
    const itemAnidado = await api.put(
      `${RUTA_COLECCION}/pac-001`,
      { nombre: 'Nombre Actualizado' },
      { adapter: adaptadorCentinela },
    );
    expect(itemAnidado.data).toBe(SIN_MOCK);

    // `PATCH` sobre el ítem: el contrato del backend solo expone `PUT`.
    const parchePlano = await api.patch(
      '/pacientes/pac-001',
      { nombre: 'Nombre Actualizado' },
      { adapter: adaptadorCentinela },
    );
    expect(parchePlano.data).toBe(SIN_MOCK);
  });
});
