import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MedicosPage } from '@/features/medicos/MedicosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { HorarioMedico, Medico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Asignación de consultorios a un médico y consultorio por bloque de horario.
 *
 * Todo se comprueba por la interfaz pública: lo que la pantalla muestra, los roles de los
 * controles y las llamadas al cliente HTTP con la URL y el cuerpo exactos. Nada por el estado
 * interno del componente.
 *
 * Las dos rutas de la asignación cuelgan del médico —`/medicos/{medicoId}/consultorios`—, no de la
 * organización: la misma forma que la agenda de horarios.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);
const postMock = vi.mocked(api.post);
const deleteMock = vi.mocked(api.delete);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios?incluirInactivos=true`;
/** Listado con las bajas: es el que pide la pantalla para poder reactivarlas (S8). */
const RUTA_LISTADO_MEDICOS = `${RUTA_MEDICOS}?incluirInactivos=true`;

/** Consultorio principal del médico: es el que la asignación nunca deja retirar. */
const PRINCIPAL: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Principal',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

/** Consultorio secundario de la organización: se usa para asignarlo y para los bloques. */
const NORTE: Consultorio = {
  id: 'con-0002',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Norte',
  direccion: 'Calle 2',
  telefono: '5551234568',
  activo: true,
  createdAt: '2026-01-03T10:00:00.000Z',
};

/** Consultorio de la organización que el médico no tiene asignado: no debe ofrecerse. */
const SUR: Consultorio = {
  id: 'con-0003',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Sur',
  direccion: 'Calle 3',
  telefono: '5551234569',
  activo: true,
  createdAt: '2026-01-04T10:00:00.000Z',
};

const MEDICO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: PRINCIPAL.id,
  nombre: 'Médico de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: '5551234567',
  tarifaBase: 500,
  activo: true,
};

const RUTA_ASIGNADOS = `/medicos/${MEDICO.id}/consultorios`;
const RUTA_HORARIOS = `/medicos/${MEDICO.id}/horarios`;

const HORARIO_EN_NORTE: HorarioMedico = {
  id: 'hor-0001',
  medicoId: MEDICO.id,
  consultorioId: NORTE.id,
  consultorioNombre: NORTE.nombre,
  diaSemana: 1,
  horaInicio: '09:00:00',
  horaFin: '13:00:00',
  duracionConsulta: 30,
};

/**
 * Deja listas las cuatro lecturas de la pantalla. La lista de asignados sale de una variable para
 * poder simular el estado del servidor después de un `POST`/`DELETE`, que responde 204 sin cuerpo.
 */
function cargarCon(opciones: {
  asignados: Consultorio[];
  horarios?: HorarioMedico[];
  consultoriosDeLaOrganizacion?: Consultorio[];
}) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_LISTADO_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
    if (url === RUTA_CONSULTORIOS) {
      return Promise.resolve(
        respuestaCon(opciones.consultoriosDeLaOrganizacion ?? [PRINCIPAL, NORTE]),
      );
    }
    if (url === RUTA_ASIGNADOS) return Promise.resolve(respuestaCon(opciones.asignados));
    if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon(opciones.horarios ?? []));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });
}

function abrirSesion(role: 'ADMIN' | 'MEDICO', medicoId: string | null = null) {
  useAuthStore.setState({
    accessToken: null,
    user: {
      id: 'usr-0001',
      email: 'prueba@correo.mx',
      role,
      tenantId: ORGANIZACION_ID,
      medicoId,
    },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
}

/** Abre el panel del médico, que es donde viven los consultorios asignados y los bloques. */
async function abrirPanelDeHorarios() {
  renderConQueryClient(<MedicosPage />);
  await screen.findByText(MEDICO.nombre);
  fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
  abrirSesion('ADMIN');
  // Los fallos se registran en consola a propósito (resumen sin secretos): se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · consultorios asignados', () => {
  it('lista los asignados y agrega uno llamando a la ruta del médico', async () => {
    let asignadosEnElServidor: Consultorio[] = [PRINCIPAL];
    getMock.mockImplementation((url: string) => {
      if (url === RUTA_ASIGNADOS) return Promise.resolve(respuestaCon(asignadosEnElServidor));
      if (url === RUTA_LISTADO_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
      if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([PRINCIPAL, NORTE]));
      if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon([]));
      return Promise.reject(new Error(`URL inesperada: ${url}`));
    });
    // `POST` idempotente de 204 sin cuerpo: el servidor guarda el vínculo y no devuelve nada.
    postMock.mockImplementation((url: string) => {
      if (url !== RUTA_ASIGNADOS) return Promise.reject(new Error(`URL inesperada: ${url}`));
      asignadosEnElServidor = [PRINCIPAL, NORTE];
      return Promise.resolve(respuestaCon(undefined));
    });

    await abrirPanelDeHorarios();

    const lista = await screen.findByRole('list', { name: /consultorios asignados/i });
    expect(within(lista).getByText(PRINCIPAL.nombre)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/consultorio a asignar/i), {
      target: { value: NORTE.id },
    });
    fireEvent.click(screen.getByRole('button', { name: /asignar consultorio/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_ASIGNADOS, { consultorioId: NORTE.id }),
    );
    // El 204 no trae cuerpo: la lista se relee del servidor y el nuevo aparece sin recargar.
    await waitFor(() => expect(within(lista).getByText(NORTE.nombre)).toBeInTheDocument());
  });

  it('muestra el mensaje del backend al quitar un consultorio que responde 409', async () => {
    const MENSAJE_DEL_BACKEND =
      'No se puede quitar el consultorio con-0002 del médico med-0001: es su consultorio principal, la referencia por defecto de su agenda y de sus pacientes. El consultorio principal no se retira';
    cargarCon({ asignados: [PRINCIPAL, NORTE] });
    deleteMock.mockRejectedValue({
      response: {
        status: 409,
        data: { codigo: 409, error: 'Conflict', mensaje: MENSAJE_DEL_BACKEND },
      },
    });

    await abrirPanelDeHorarios();

    const lista = await screen.findByRole('list', { name: /consultorios asignados/i });
    await within(lista).findByText(NORTE.nombre);
    fireEvent.click(screen.getByRole('button', { name: `Quitar ${NORTE.nombre}` }));

    // El motivo lo pone el backend (es el principal o tiene bloques): un texto genérico dejaría al
    // administrador sin saber qué cambiar antes de reintentar.
    expect(await screen.findByText(MENSAJE_DEL_BACKEND)).toBeInTheDocument();
    await waitFor(() =>
      expect(deleteMock).toHaveBeenCalledWith(`${RUTA_ASIGNADOS}/${NORTE.id}`),
    );
  });
});

describe('MedicosPage · consultorio del bloque', () => {
  it('crea el bloque en el consultorio elegido y ofrece solo los asignados', async () => {
    cargarCon({
      asignados: [PRINCIPAL, NORTE],
      consultoriosDeLaOrganizacion: [PRINCIPAL, NORTE, SUR],
    });
    postMock.mockResolvedValue(
      respuestaCon({ ...HORARIO_EN_NORTE, id: 'hor-0002', consultorioId: PRINCIPAL.id }),
    );

    await abrirPanelDeHorarios();

    const selector = (await screen.findByLabelText(/consultorio del bloque/i)) as HTMLSelectElement;
    // Por defecto el principal del médico; el consultorio no asignado no se ofrece.
    await waitFor(() => expect(selector.options).toHaveLength(2));
    expect(selector.value).toBe(PRINCIPAL.id);
    const ofrecidos = Array.from(selector.options).map((o) => o.textContent);
    expect(ofrecidos).toEqual([PRINCIPAL.nombre, NORTE.nombre]);

    fireEvent.change(selector, { target: { value: NORTE.id } });
    fireEvent.click(screen.getByRole('button', { name: /agregar bloque/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_HORARIOS, {
        diaSemana: 1,
        horaInicio: '09:00:00',
        horaFin: '14:00:00',
        duracionConsulta: 30,
        consultorioId: NORTE.id,
      }),
    );
  });

  it('muestra el nombre del consultorio en cada fila y no ofrece asignar a un MEDICO', async () => {
    abrirSesion('MEDICO', MEDICO.id);
    cargarCon({ asignados: [PRINCIPAL, NORTE], horarios: [HORARIO_EN_NORTE] });

    await abrirPanelDeHorarios();

    const bloques = await screen.findByRole('list', { name: /bloques de disponibilidad/i });
    const fila = within(bloques).getByRole('listitem');
    expect(within(fila).getByText(NORTE.nombre)).toBeInTheDocument();

    // La lectura de los asignados admite al propio médico; asignar y quitar son solo de ADMIN.
    expect(screen.queryByRole('button', { name: /asignar consultorio/i })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: `Quitar ${NORTE.nombre}` }),
    ).not.toBeInTheDocument();
  });
});
