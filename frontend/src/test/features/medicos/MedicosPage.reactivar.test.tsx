import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MedicosPage } from '@/features/medicos/MedicosPage';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Reactivación de un médico dado de baja (S4): una fila inactiva ofrece «Reactivar» y una activa
 * «Desactivar», nunca las dos, y el `POST /medicos/{id}/activar` responde 204 sin cuerpo.
 *
 * El error de la operación ocurre con el diálogo de baja cerrado, así que se lee en la página.
 * Todo se comprueba por la interfaz pública y se afirma la URL exacta del contrato.
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

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios?incluirInactivos=true`;
/** Listado con las bajas: es el que pide la pantalla para poder reactivarlas (S8). */
const RUTA_LISTADO_MEDICOS = `${RUTA_MEDICOS}?incluirInactivos=true`;
const RUTA_ACTIVAR = (id: string) => `/medicos/${id}/activar`;

/** Mensaje del backend para S7: el consultorio principal está dado de baja. */
const MENSAJE_PRINCIPAL_INACTIVO =
  'No se puede reactivar este médico: su consultorio principal está dado de baja. Reactiva primero el consultorio y después al médico';

const CONSULTORIO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio de prueba',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const MEDICO_INACTIVO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: CONSULTORIO.id,
  nombre: 'Médico de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: '5551234567',
  tarifaBase: 500,
  activo: false,
};

const MEDICO_ACTIVO: Medico = { ...MEDICO_INACTIVO, id: 'med-0002', nombre: 'Médica activa', activo: true };

/** Deja listas las dos lecturas del directorio: médicos y consultorios. */
function cargarCon(medicos: Medico[]) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_LISTADO_MEDICOS) return Promise.resolve(respuestaCon(medicos));
    if (url === RUTA_CONSULTORIOS) return Promise.resolve(respuestaCon([CONSULTORIO]));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });
}

/** Fila de la tabla donde aparece el nombre del médico. */
function filaDe(medico: Medico): HTMLElement {
  const fila = screen.getByText(medico.nombre).closest('tr');
  if (!fila) throw new Error(`El médico ${medico.nombre} no está en la tabla`);
  return fila;
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
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
  // Los fallos se registran en consola a propósito (resumen sin secretos); se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · reactivación de un médico inactivo (S4)', () => {
  it('una fila inactiva ofrece Reactivar y no Desactivar', async () => {
    cargarCon([MEDICO_INACTIVO]);

    render(<MedicosPage />);

    await screen.findByText(MEDICO_INACTIVO.nombre);
    const fila = filaDe(MEDICO_INACTIVO);
    expect(fila).toHaveTextContent('Inactivo');
    expect(fila.querySelector('button[title="Reactivar médico"]')).not.toBeNull();
    expect(fila.querySelector('button[title="Desactivar médico"]')).toBeNull();
  });

  it('llama a la ruta de activación y deja la fila activa sin recargar el directorio', async () => {
    cargarCon([MEDICO_INACTIVO]);
    postMock.mockResolvedValue(respuestaCon<void>(undefined));

    render(<MedicosPage />);

    await screen.findByText(MEDICO_INACTIVO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(postMock.mock.calls[0][0]).toBe(RUTA_ACTIVAR(MEDICO_INACTIVO.id));

    await waitFor(() => expect(filaDe(MEDICO_INACTIVO)).toHaveTextContent('Activo'));
    expect(filaDe(MEDICO_INACTIVO).querySelector('button[title="Reactivar médico"]')).toBeNull();
    expect(filaDe(MEDICO_INACTIVO).querySelector('button[title="Desactivar médico"]')).not.toBeNull();
    expect(await screen.findByText(/médico reactivado correctamente/i)).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledTimes(2);
    expect(getMock).toHaveBeenCalledWith(RUTA_LISTADO_MEDICOS);
  });

  it('muestra el rechazo en la página y deja la fila inactiva', async () => {
    cargarCon([MEDICO_INACTIVO]);
    postMock.mockRejectedValue(new Error('sin conexión'));

    render(<MedicosPage />);

    await screen.findByText(MEDICO_INACTIVO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/no fue posible reactivar al médico/i)).toBeInTheDocument();
    expect(screen.queryByText(/no fue posible obtener el directorio de médicos/i)).not.toBeInTheDocument();
    expect(filaDe(MEDICO_INACTIVO)).toHaveTextContent('Inactivo');
    expect(filaDe(MEDICO_INACTIVO).querySelector('button[title="Reactivar médico"]')).not.toBeNull();
  });

  it('muestra el mensaje del backend cuando la reactivación se rechaza por el consultorio principal (S7)', async () => {
    cargarCon([MEDICO_INACTIVO]);
    postMock.mockRejectedValue({ response: { data: { mensaje: MENSAJE_PRINCIPAL_INACTIVO } } });

    render(<MedicosPage />);

    await screen.findByText(MEDICO_INACTIVO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    expect(await screen.findByText(MENSAJE_PRINCIPAL_INACTIVO)).toBeInTheDocument();
    expect(screen.queryByText(/revisa la conexión con el servicio/i)).not.toBeInTheDocument();
    expect(filaDe(MEDICO_INACTIVO)).toHaveTextContent('Inactivo');
  });

  it('una fila activa sigue ofreciendo Desactivar y no Reactivar', async () => {
    cargarCon([MEDICO_ACTIVO]);

    render(<MedicosPage />);

    await screen.findByText(MEDICO_ACTIVO.nombre);
    const fila = filaDe(MEDICO_ACTIVO);
    expect(fila.querySelector('button[title="Desactivar médico"]')).not.toBeNull();
    expect(fila.querySelector('button[title="Reactivar médico"]')).toBeNull();
  });
});
