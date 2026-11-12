import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { ConsultoriosPage } from '@/features/consultorios/ConsultoriosPage';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Pantalla de Consultorios: listado, alta y estados de carga.
 *
 * Todo se comprueba por la interfaz pública (roles, textos y las llamadas al cliente HTTP con la
 * URL exacta), nunca por el estado interno del componente.
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

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Organización de la sesión: es el claim `tenantId` y la dueña de la colección. */
const ORGANIZACION_ID = 'org-001';

/** La colección cuelga de la organización: `GET/POST /organizaciones/{id}/consultorios`. */
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/consultorios`;

const CONSULTORIO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio de prueba',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

function sesionAdmin() {
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
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  vi.mocked(api.put).mockReset();
  vi.mocked(api.delete).mockReset();
  sesionAdmin();
  // Los fallos se registran en consola a propósito (resumen sin secretos): se silencia la salida.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('ConsultoriosPage · listado', () => {
  it('muestra los consultorios de la colección de la organización', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO]));

    render(<ConsultoriosPage />);

    expect(await screen.findByText('Consultorio de prueba')).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledWith(RUTA_COLECCION);
  });
});

describe('ConsultoriosPage · alta', () => {
  it('registra en la URL exacta de la colección con el contrato del backend', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO]));
    postMock.mockResolvedValue(
      respuestaCon({ ...CONSULTORIO, id: 'con-0002', nombre: 'Consultorio nuevo' }),
    );

    render(<ConsultoriosPage />);
    await screen.findByText('Consultorio de prueba');

    fireEvent.click(screen.getByRole('button', { name: /nuevo consultorio/i }));
    fireEvent.change(screen.getByLabelText(/nombre/i), {
      target: { value: 'Consultorio nuevo' },
    });
    fireEvent.click(screen.getByRole('button', { name: /registrar consultorio/i }));

    await waitFor(() =>
      expect(postMock).toHaveBeenCalledWith(RUTA_COLECCION, {
        nombre: 'Consultorio nuevo',
        direccion: null,
        telefono: null,
      }),
    );
  });
});

describe('ConsultoriosPage · estados de carga', () => {
  it('distingue un fallo de carga de un directorio vacío y permite reintentar', async () => {
    getMock
      .mockRejectedValueOnce(new Error('sin conexión'))
      .mockResolvedValueOnce(respuestaCon([CONSULTORIO]));

    render(<ConsultoriosPage />);

    expect(
      await screen.findByText(/no se pudieron cargar los consultorios/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /reintentar/i }));

    expect(await screen.findByText('Consultorio de prueba')).toBeInTheDocument();
  });
});
