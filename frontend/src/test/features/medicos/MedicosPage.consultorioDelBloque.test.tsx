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
 * Selector de consultorio del formulario de bloque (S13).
 *
 * El backend rechaza con 409 un bloque en un consultorio dado de baja (S11), así que la pantalla no
 * puede ofrecerlo al crear. La excepción es la edición: si el bloque ya vive en un consultorio de
 * baja, ese consultorio se muestra igual y corregir la hora tiene que funcionar —editar la hora no
 * es mover el bloque—, lo que se traduce en no reenviar el `consultorioId` cuando no cambió.
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
const putMock = vi.mocked(api.put);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos?incluirInactivos=true`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios?incluirInactivos=true`;

const CENTRO: Consultorio = {
  id: 'con-centro',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Centro',
  direccion: null,
  telefono: null,
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const SUR: Consultorio = { ...CENTRO, id: 'con-sur', nombre: 'Consultorio Sur' };

const NORTE: Consultorio = {
  ...CENTRO,
  id: 'con-norte',
  nombre: 'Consultorio Norte',
  activo: false,
};

const MEDICO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: CENTRO.id,
  nombre: 'Médica de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: null,
  tarifaBase: 500,
  activo: true,
};

const BLOQUE_EN_NORTE: HorarioMedico = {
  id: 'hor-0001',
  medicoId: MEDICO.id,
  consultorioId: NORTE.id,
  consultorioNombre: NORTE.nombre,
  diaSemana: 1,
  horaInicio: '09:00:00',
  horaFin: '13:00:00',
  duracionConsulta: 30,
};

const RUTA_ASIGNADOS = `/medicos/${MEDICO.id}/consultorios`;
const RUTA_HORARIOS = `/medicos/${MEDICO.id}/horarios`;

function cargarPanel(asignados: Consultorio[], horarios: HorarioMedico[]) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
    if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CENTRO, SUR, NORTE]));
    if (url === RUTA_ASIGNADOS) return Promise.resolve(respuestaCon(asignados));
    if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon(horarios));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });
}

/** Abre el panel de horarios del médico desde su fila. */
async function abrirPanelDeHorarios() {
  renderConQueryClient(<MedicosPage />);
  await screen.findByText(MEDICO.nombre);
  fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
  await screen.findByLabelText('Consultorio del bloque');
  // El selector nace deshabilitado mientras la consulta de asignados está en vuelo (TanStack Query
  // la dispara en un efecto, no de forma síncrona con el clic): hay que esperar a que resuelva
  // antes de mirar sus opciones.
  await waitFor(() => expect(screen.getByLabelText('Consultorio del bloque')).not.toBeDisabled());
}

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  putMock.mockReset();
  vi.mocked(api.delete).mockReset();
  useAuthStore.setState({
    accessToken: null,
    user: { id: 'usr-0001', email: 'admin@correo.mx', role: 'ADMIN', tenantId: ORGANIZACION_ID },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · consultorio del bloque (S13)', () => {
  it('al crear no ofrece un consultorio dado de baja, aunque esté asignado', async () => {
    cargarPanel([CENTRO, NORTE], []);

    await abrirPanelDeHorarios();

    const seleccion = screen.getByLabelText('Consultorio del bloque');
    expect(within(seleccion).queryByRole('option', { name: NORTE.nombre })).toBeNull();
    expect(within(seleccion).getByRole('option', { name: CENTRO.nombre })).toBeInTheDocument();
  });

  it('ofrece todos los consultorios activos asignados', async () => {
    cargarPanel([CENTRO, SUR, NORTE], []);

    await abrirPanelDeHorarios();

    const seleccion = screen.getByLabelText('Consultorio del bloque');
    expect(within(seleccion).getByRole('option', { name: CENTRO.nombre })).toBeInTheDocument();
    expect(within(seleccion).getByRole('option', { name: SUR.nombre })).toBeInTheDocument();
    expect(within(seleccion).queryByRole('option', { name: NORTE.nombre })).toBeNull();
  });

  it('al editar un bloque de un consultorio de baja lo muestra seleccionado y guarda la hora', async () => {
    cargarPanel([CENTRO, NORTE], [BLOQUE_EN_NORTE]);
    putMock.mockResolvedValue(
      respuestaCon<HorarioMedico>({ ...BLOQUE_EN_NORTE, horaFin: '14:00:00' }),
    );

    await abrirPanelDeHorarios();

    fireEvent.click(await screen.findByTitle('Editar bloque'));

    const seleccion = screen.getByLabelText('Consultorio del bloque');
    expect(
      within(seleccion).getByRole('option', { name: NORTE.nombre }),
    ).toBeInTheDocument();
    expect((seleccion as HTMLSelectElement).value).toBe(NORTE.id);

    fireEvent.change(screen.getByLabelText(/hora fin/i), { target: { value: '14:00' } });
    fireEvent.click(screen.getByRole('button', { name: /guardar bloque/i }));

    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1));
    expect(putMock.mock.calls[0][0]).toBe(`/horarios/${BLOQUE_EN_NORTE.id}`);
    // Corregir la hora no es mover el bloque: sin `consultorioId` el backend conserva el actual.
    expect(putMock.mock.calls[0][1]).toEqual({
      diaSemana: 1,
      horaInicio: '09:00:00',
      horaFin: '14:00:00',
      duracionConsulta: 30,
    });
    expect(await screen.findByText(/14:00/)).toBeInTheDocument();
  });
});
