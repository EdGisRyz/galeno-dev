import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MedicosPage } from '@/features/medicos/MedicosPage';
import type { HorarioMedico, Medico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Pantalla de Médicos y su panel de horarios.
 *
 * Se comprueba por la interfaz pública: textos, roles y las llamadas al cliente HTTP con la URL
 * exacta. Hay dos reglas propias que se cubren aparte:
 *
 * - El backend permite a MEDICO gestionar **su** agenda y le responde 403 con la de otro; la
 *   pantalla replica esa regla con el claim `medicoId`.
 * - El borrado de un bloque es físico (la tabla no tiene baja lógica), así que la confirmación
 *   es la única barrera: el `DELETE` no puede salir antes de aceptarla.
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
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios`;

const CONSULTORIO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio de prueba',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const MEDICO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: CONSULTORIO.id,
  nombre: 'Médico de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: '5551234567',
  tarifaBase: 500,
  activo: true,
};

const HORARIO: HorarioMedico = {
  id: 'hor-0001',
  medicoId: MEDICO.id,
  diaSemana: 1,
  horaInicio: '09:00:00',
  horaFin: '13:00:00',
  duracionConsulta: 30,
};

const RUTA_HORARIOS = `/medicos/${MEDICO.id}/horarios`;

/** Deja listas las tres lecturas de la pantalla: médicos, consultorios y una agenda concreta. */
function cargarCon(horarios: HorarioMedico[] = []) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
    if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
    if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon(horarios));
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

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
  abrirSesion('ADMIN');
  // Los fallos se registran en consola a propósito (resumen sin secretos): se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · listado', () => {
  it('muestra los médicos y el nombre de su consultorio', async () => {
    cargarCon();

    render(<MedicosPage />);

    expect(await screen.findByText('Médico de prueba')).toBeInTheDocument();
    expect(screen.getByText('Consultorio de prueba')).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledWith(RUTA_MEDICOS);
  });
});

describe('MedicosPage · alta', () => {
  it('registra en la colección de la organización con el contrato del backend', async () => {
    cargarCon();
    postMock.mockResolvedValue(respuestaCon({ ...MEDICO, id: 'med-0002', nombre: 'Médico nuevo' }));

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));
    fireEvent.change(screen.getByLabelText(/nombre/i), { target: { value: 'Médico nuevo' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_MEDICOS, {
        consultorioId: CONSULTORIO.id,
        nombre: 'Médico nuevo',
        especialidad: null,
        cedula: null,
        telefono: null,
        tarifaBase: null,
      }),
    );
  });
});

describe('MedicosPage · estados de carga', () => {
  it('distingue un fallo de carga y permite reintentar', async () => {
    getMock
      .mockRejectedValueOnce(new Error('sin conexión'))
      .mockImplementation((url: string) => {
        if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon([MEDICO]));
        if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
        return Promise.resolve(respuestaCon([]));
      });

    render(<MedicosPage />);

    expect(await screen.findByText(/no se pudieron cargar los médicos/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /reintentar/i }));

    expect(await screen.findByText('Médico de prueba')).toBeInTheDocument();
  });
});

describe('MedicosPage · horarios según el rol', () => {
  it('deja que un MEDICO gestione la agenda propia', async () => {
    abrirSesion('MEDICO', MEDICO.id);
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));

    expect(await screen.findByRole('button', { name: /agregar bloque/i })).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledWith(RUTA_HORARIOS);
  });

  it('no ofrece editar la agenda de otro médico', async () => {
    abrirSesion('MEDICO', 'med-ajeno');
    cargarCon();

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));

    // El panel se abre (el médico puede consultarlo), pero sin acciones de escritura.
    expect(await screen.findByText(/horarios de médico de prueba/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /agregar bloque/i })).not.toBeInTheDocument();
  });
});

describe('MedicosPage · borrado de un bloque', () => {
  it('pide confirmación y no llama al servicio hasta aceptarla', async () => {
    cargarCon([HORARIO]);

    render(<MedicosPage />);
    await screen.findByText('Médico de prueba');

    fireEvent.click(screen.getByRole('button', { name: /horarios/i }));
    fireEvent.click(await screen.findByTitle('Eliminar bloque'));

    const titulo = await screen.findByText('Eliminar bloque de disponibilidad');
    expect(deleteMock).not.toHaveBeenCalled();

    const confirmacion = titulo.closest('[role="dialog"]');
    expect(confirmacion).not.toBeNull();
    fireEvent.click(within(confirmacion as HTMLElement).getByRole('button', { name: /eliminar bloque/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith(`/horarios/${HORARIO.id}`));
  });
});
