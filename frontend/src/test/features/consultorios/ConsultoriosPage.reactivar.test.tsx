import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { ConsultoriosPage } from '@/features/consultorios/ConsultoriosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Reactivación de un consultorio dado de baja (S4): una fila inactiva ofrece «Reactivar» y una
 * activa «Desactivar», nunca las dos, y el `POST /consultorios/{id}/activar` responde 204 sin
 * cuerpo. Es el caso del consultorio que se dio de baja por refacción.
 *
 * El error de la operación ocurre con el diálogo de baja cerrado, así que se lee en la página.
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
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/consultorios`;
/** Listado con las bajas: es el que pide la pantalla para poder reactivarlas (S8). */
const RUTA_LISTADO_CONSULTORIOS = `${RUTA_COLECCION}?incluirInactivos=true`;
const RUTA_ACTIVAR = (id: string) => `/consultorios/${id}/activar`;

const CONSULTORIO_INACTIVO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio en refacción',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: false,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const CONSULTORIO_ACTIVO: Consultorio = {
  ...CONSULTORIO_INACTIVO,
  id: 'con-0002',
  nombre: 'Consultorio activo',
  activo: true,
};

/** Fila de la tabla donde aparece el nombre del consultorio. */
function filaDe(consultorio: Consultorio): HTMLElement {
  const fila = screen.getByText(consultorio.nombre).closest('tr');
  if (!fila) throw new Error(`El consultorio ${consultorio.nombre} no está en la tabla`);
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

describe('ConsultoriosPage · reactivación de un consultorio inactivo (S4)', () => {
  it('una fila inactiva ofrece Reactivar y no Desactivar', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_INACTIVO]));

    renderConQueryClient(<ConsultoriosPage />);

    await screen.findByText(CONSULTORIO_INACTIVO.nombre);
    const fila = filaDe(CONSULTORIO_INACTIVO);
    expect(fila).toHaveTextContent('Inactivo');
    expect(fila.querySelector('button[title="Reactivar consultorio"]')).not.toBeNull();
    expect(fila.querySelector('button[title="Desactivar consultorio"]')).toBeNull();
  });

  it('llama a la ruta de activación y deja la fila activa sin recargar el directorio', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_INACTIVO]));
    postMock.mockResolvedValue(respuestaCon<void>(undefined));

    renderConQueryClient(<ConsultoriosPage />);

    await screen.findByText(CONSULTORIO_INACTIVO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(postMock.mock.calls[0][0]).toBe(RUTA_ACTIVAR(CONSULTORIO_INACTIVO.id));

    await waitFor(() => expect(filaDe(CONSULTORIO_INACTIVO)).toHaveTextContent('Activo'));
    expect(filaDe(CONSULTORIO_INACTIVO).querySelector('button[title="Reactivar consultorio"]')).toBeNull();
    expect(
      filaDe(CONSULTORIO_INACTIVO).querySelector('button[title="Desactivar consultorio"]'),
    ).not.toBeNull();
    expect(await screen.findByText(/consultorio reactivado correctamente/i)).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith(RUTA_LISTADO_CONSULTORIOS);
  });

  it('muestra el rechazo en la página y deja la fila inactiva', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_INACTIVO]));
    postMock.mockRejectedValue(new Error('sin conexión'));

    renderConQueryClient(<ConsultoriosPage />);

    await screen.findByText(CONSULTORIO_INACTIVO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/no fue posible reactivar el consultorio/i)).toBeInTheDocument();
    expect(
      screen.queryByText(/no fue posible obtener el directorio de consultorios/i),
    ).not.toBeInTheDocument();
    expect(filaDe(CONSULTORIO_INACTIVO)).toHaveTextContent('Inactivo');
    expect(
      filaDe(CONSULTORIO_INACTIVO).querySelector('button[title="Reactivar consultorio"]'),
    ).not.toBeNull();
  });

  it('una fila activa sigue ofreciendo Desactivar y no Reactivar', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_ACTIVO]));

    renderConQueryClient(<ConsultoriosPage />);

    await screen.findByText(CONSULTORIO_ACTIVO.nombre);
    const fila = filaDe(CONSULTORIO_ACTIVO);
    expect(fila.querySelector('button[title="Desactivar consultorio"]')).not.toBeNull();
    expect(fila.querySelector('button[title="Reactivar consultorio"]')).toBeNull();
  });
});
