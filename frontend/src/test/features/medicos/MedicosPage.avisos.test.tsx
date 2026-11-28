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
 * S1-S3 · el aviso de un alta rechazada se ve **dentro** del diálogo.
 *
 * El 409 de la cédula repetida existía, pero se pintaba en la página, detrás del modal: la persona
 * veía un botón que no respondía y tenía que cerrar el diálogo para descubrir el motivo, perdiendo
 * lo escrito. Aquí se fija lo que tiene que pasar: el mensaje del backend **dentro** del
 * `role="dialog"`, el formulario con el valor que se estaba cargando, y el mensaje **una sola vez**.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  // El store conecta el cliente HTTP al cargarse; aquí no hay red que conectar.
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);
const postMock = vi.mocked(api.post);

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios?incluirInactivos=true`;
/** Listado con las bajas: es el que pide la pantalla para poder reactivarlas (S8). */
const RUTA_LISTADO_MEDICOS = `${RUTA_MEDICOS}?incluirInactivos=true`;

/** Motivo que el backend adjunta al 409 de la cédula repetida. */
const MENSAJE_DUPLICADO =
  'Ya existe un médico con esa cédula profesional en la organización.';

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Rechazo HTTP con la forma que consumen `resumenDeErrorHttp` y `mensajeDelBackend`. */
function rechazoHttp(estado: number, mensaje: string): Error {
  return Object.assign(new Error(`Request failed with status code ${estado}`), {
    config: { method: 'post', url: RUTA_MEDICOS },
    response: { status: estado, data: { codigo: estado, error: 'Conflict', mensaje } },
  });
}

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

const RUTA_HORARIOS = `/medicos/${MEDICO.id}/horarios`;
const RUTA_CONSULTORIOS_ASIGNADOS = `/medicos/${MEDICO.id}/consultorios`;

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  vi.mocked(api.delete).mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});

  getMock.mockImplementation((url: string) => {
    if (url === RUTA_LISTADO_MEDICOS) return Promise.resolve(respuestaCon<Medico[]>([MEDICO]));
    if (url === RUTA_CONSULTORIOS) {
      return Promise.resolve(respuestaCon<Consultorio[]>([CONSULTORIO]));
    }
    if (url === RUTA_CONSULTORIOS_ASIGNADOS) {
      return Promise.resolve(respuestaCon<Consultorio[]>([CONSULTORIO]));
    }
    if (url === RUTA_HORARIOS) return Promise.resolve(respuestaCon<HorarioMedico[]>([]));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });

  useAuthStore.setState({
    accessToken: null,
    user: {
      id: 'usr-0001',
      email: 'prueba@correo.mx',
      role: 'ADMIN',
      tenantId: ORGANIZACION_ID,
      medicoId: null,
    },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
});

describe('MedicosPage · aviso de guardado dentro del modal', () => {
  it('un 409 muestra el mensaje del backend dentro del diálogo y conserva lo escrito', async () => {
    postMock.mockRejectedValue(rechazoHttp(409, MENSAJE_DUPLICADO));

    renderConQueryClient(<MedicosPage />);
    await screen.findByText(MEDICO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));

    fireEvent.change(screen.getByLabelText(/^nombre/i), {
      target: { value: 'Médico repetido' },
    });
    fireEvent.change(screen.getByLabelText(/cédula/i), { target: { value: '7654321' } });
    fireEvent.click(screen.getByRole('button', { name: /registrar médico/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));

    // El diálogo sigue abierto y el motivo se lee sin cerrarlo (S1, S2).
    const dialogo = screen.getByRole('dialog');
    expect(await within(dialogo).findByText(MENSAJE_DUPLICADO)).toBeInTheDocument();
    expect((screen.getByLabelText(/^nombre/i) as HTMLInputElement).value).toBe('Médico repetido');

    // El mismo mensaje no aparece dos veces: el aviso de página no lo repite (S1).
    expect(screen.getAllByText(MENSAJE_DUPLICADO)).toHaveLength(1);
  });
});
