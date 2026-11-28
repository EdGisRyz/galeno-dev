import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { ConsultoriosPage } from '@/features/consultorios/ConsultoriosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Baja de un consultorio (S6): el backend responde 409 cuando es el consultorio principal de un
 * médico activo, y su mensaje explica el camino —dar de baja primero al médico—. Ese rechazo se
 * lee en la página, porque el diálogo de confirmación ya se cerró cuando ocurre.
 *
 * La baja permitida —principal de un médico inactivo— la decide el servicio y no la pantalla; aquí
 * se conserva la baja exitosa, que deja la fila inactiva con la opción de reactivarla.
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
const deleteMock = vi.mocked(api.delete);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';

/** Mensaje del backend para S6: el consultorio es el principal de un médico activo. */
const MENSAJE_PRINCIPAL_DE_MEDICO_ACTIVO =
  'No se puede dar de baja este consultorio: es el consultorio principal de un médico activo, la referencia por defecto de su agenda. Da de baja primero al médico y después al consultorio';

const CONSULTORIO_ACTIVO: Consultorio = {
  id: 'con-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio principal',
  direccion: 'Calle 1',
  telefono: '5551234567',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

function filaDe(consultorio: Consultorio): HTMLElement {
  const fila = screen.getByText(consultorio.nombre).closest('tr');
  if (!fila) throw new Error(`El consultorio ${consultorio.nombre} no está en la tabla`);
  return fila;
}

/** Pulsa «Desactivar» en la fila y confirma en el diálogo, que es el flujo real de la baja. */
async function confirmarBaja(consultorio: Consultorio) {
  fireEvent.click(within(filaDe(consultorio)).getByRole('button', { name: /desactivar/i }));
  const dialogo = screen.getByRole('dialog');
  fireEvent.click(within(dialogo).getByRole('button', { name: /desactivar/i }));
}

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
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

describe('ConsultoriosPage · baja de un consultorio', () => {
  it('muestra el mensaje del backend cuando es el principal de un médico activo (S6)', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_ACTIVO]));
    deleteMock.mockRejectedValue({
      response: { status: 409, data: { mensaje: MENSAJE_PRINCIPAL_DE_MEDICO_ACTIVO } },
    });

    renderConQueryClient(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO_ACTIVO.nombre);

    await confirmarBaja(CONSULTORIO_ACTIVO);

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1));
    expect(deleteMock.mock.calls[0][0]).toBe(`/consultorios/${CONSULTORIO_ACTIVO.id}`);
    expect(await screen.findByText(MENSAJE_PRINCIPAL_DE_MEDICO_ACTIVO)).toBeInTheDocument();
    expect(screen.queryByText(/revisa la conexión con el servicio/i)).not.toBeInTheDocument();
    expect(filaDe(CONSULTORIO_ACTIVO)).toHaveTextContent('Activo');
    expect(
      within(filaDe(CONSULTORIO_ACTIVO)).getByRole('button', { name: /desactivar/i }),
    ).toBeInTheDocument();
  });

  it('deja la fila inactiva con Reactivar cuando la baja se acepta', async () => {
    getMock.mockResolvedValue(respuestaCon([CONSULTORIO_ACTIVO]));
    deleteMock.mockResolvedValue(respuestaCon<void>(undefined));

    renderConQueryClient(<ConsultoriosPage />);
    await screen.findByText(CONSULTORIO_ACTIVO.nombre);

    await confirmarBaja(CONSULTORIO_ACTIVO);

    await waitFor(() => expect(filaDe(CONSULTORIO_ACTIVO)).toHaveTextContent('Inactivo'));
    expect(
      within(filaDe(CONSULTORIO_ACTIVO)).getByRole('button', { name: /reactivar/i }),
    ).toBeInTheDocument();
    expect(
      within(filaDe(CONSULTORIO_ACTIVO)).queryByRole('button', { name: /desactivar/i }),
    ).not.toBeInTheDocument();
    expect(await screen.findByText(/consultorio desactivado correctamente/i)).toBeInTheDocument();
  });
});
