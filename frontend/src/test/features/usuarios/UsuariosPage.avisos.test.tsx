import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { UsuariosPage } from '@/features/usuarios/UsuariosPage';
import type { Usuario } from '@/features/usuarios/usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S1-S3 · el aviso de un alta rechazada se ve **dentro** del diálogo.
 *
 * Cuando el alta de una cuenta choca con el correo ya ocupado, el 409 existía pero se pintaba en la
 * página, detrás del modal: la persona veía un botón que no respondía y tenía que cerrar el diálogo
 * para descubrir el motivo, perdiendo lo escrito. Aquí se fija lo que tiene que pasar: el mensaje
 * del backend **dentro** del `role="dialog"`, el formulario con el valor que se estaba cargando, y
 * el mensaje **una sola vez** —el aviso de página no se duplica—.
 *
 * Todo se comprueba por la interfaz pública: el diálogo, el texto visible y las llamadas al cliente
 * HTTP.
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
const RUTA_CUENTAS = `/organizaciones/${ORGANIZACION_ID}/usuarios`;
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;

/** Motivo que el backend adjunta al 409 del correo repetido. */
const MENSAJE_DUPLICADO =
  'Ya existe una cuenta con el correo recepcion@correo.mx en esta organización.';

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Rechazo HTTP con la forma que consumen `resumenDeErrorHttp` y `mensajeDelBackend`. */
function rechazoHttp(estado: number, mensaje: string): Error {
  return Object.assign(new Error(`Request failed with status code ${estado}`), {
    config: { method: 'post', url: RUTA_CUENTAS },
    response: { status: estado, data: { codigo: estado, error: 'Conflict', mensaje } },
  });
}

const CUENTA_PROPIA: Usuario = {
  id: 'usr-admin-1',
  organizacionId: ORGANIZACION_ID,
  email: 'admin@dev-auth.local',
  rol: 'ADMIN',
  activo: true,
  createdAt: '2026-01-01T10:00:00.000Z',
};

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  vi.mocked(api.delete).mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});

  getMock.mockImplementation(((url: string) =>
    Promise.resolve(
      url === RUTA_MEDICOS ? respuestaCon<Medico[]>([]) : respuestaCon([CUENTA_PROPIA]),
    )) as typeof api.get);

  useAuthStore.setState({
    accessToken: null,
    user: {
      id: CUENTA_PROPIA.id,
      email: CUENTA_PROPIA.email,
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

describe('UsuariosPage · aviso de guardado dentro del modal', () => {
  it('un 409 muestra el mensaje del backend dentro del diálogo y conserva el correo escrito', async () => {
    postMock.mockRejectedValue(rechazoHttp(409, MENSAJE_DUPLICADO));

    render(<UsuariosPage />);
    await screen.findByText(CUENTA_PROPIA.email);
    fireEvent.click(screen.getByRole('button', { name: /nueva cuenta/i }));

    fireEvent.change(screen.getByLabelText(/^correo/i), {
      target: { value: 'recepcion@correo.mx' },
    });
    // El nombre es obligatorio desde S1; el aviso que se prueba aquí es el del 409 del correo.
    fireEvent.change(screen.getByLabelText(/^nombre/i), {
      target: { value: 'Recepción Matutina' },
    });
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));

    // El diálogo sigue abierto y el motivo se lee sin cerrarlo (S1, S2).
    const dialogo = screen.getByRole('dialog');
    expect(await within(dialogo).findByText(MENSAJE_DUPLICADO)).toBeInTheDocument();
    expect((screen.getByLabelText(/^correo/i) as HTMLInputElement).value).toBe(
      'recepcion@correo.mx',
    );

    // El mismo mensaje no aparece dos veces: el aviso de página no lo repite (S1).
    expect(screen.getAllByText(MENSAJE_DUPLICADO)).toHaveLength(1);
  });
});
