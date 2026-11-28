import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { ConsultoriosPage } from '@/features/consultorios/ConsultoriosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S1-S3 · el aviso de un alta rechazada se ve **dentro** del diálogo.
 *
 * El 409 del nombre repetido existía, pero se pintaba en la página, detrás del modal: la persona
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
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/consultorios`;

/** Motivo que el backend adjunta al 409 del nombre repetido. */
const MENSAJE_DUPLICADO =
  'Ya existe un consultorio con ese nombre en la organización.';

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Rechazo HTTP con la forma que consumen `resumenDeErrorHttp` y `mensajeDelBackend`. */
function rechazoHttp(estado: number, mensaje: string): Error {
  return Object.assign(new Error(`Request failed with status code ${estado}`), {
    config: { method: 'post', url: RUTA_COLECCION },
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

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  vi.mocked(api.delete).mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});

  getMock.mockResolvedValue(respuestaCon([CONSULTORIO]));

  useAuthStore.setState({
    accessToken: null,
    user: {
      id: 'usr-0001',
      email: 'prueba@correo.mx',
      role: 'ADMIN',
      tenantId: ORGANIZACION_ID,
    },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
});

describe('ConsultoriosPage · aviso de guardado dentro del modal', () => {
  it('un 409 muestra el mensaje del backend dentro del diálogo y conserva el nombre escrito', async () => {
    postMock.mockRejectedValue(rechazoHttp(409, MENSAJE_DUPLICADO));

    renderConQueryClient(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO.nombre);
    fireEvent.click(screen.getByRole('button', { name: /nuevo consultorio/i }));

    fireEvent.change(screen.getByLabelText(/^nombre/i), {
      target: { value: 'Consultorio repetido' },
    });
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));

    // El diálogo sigue abierto y el motivo se lee sin cerrarlo (S1, S2).
    const dialogo = screen.getByRole('dialog');
    expect(await within(dialogo).findByText(MENSAJE_DUPLICADO)).toBeInTheDocument();
    expect((screen.getByLabelText(/^nombre/i) as HTMLInputElement).value).toBe(
      'Consultorio repetido',
    );

    // El mismo mensaje no aparece dos veces: el aviso de página no lo repite (S1).
    expect(screen.getAllByText(MENSAJE_DUPLICADO)).toHaveLength(1);
  });
});
