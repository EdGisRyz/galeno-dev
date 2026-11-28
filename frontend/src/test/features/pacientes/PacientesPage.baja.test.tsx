import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Baja de un paciente: `DELETE /pacientes/{id}` responde 204 sin cuerpo. La baja es lógica, así que
 * la fila se marca inactiva en el estado local y aparece la acción para reactivarla; sin esta mitad
 * del flujo, la reactivación nunca sería alcanzable desde la pantalla.
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
const deleteMock = vi.mocked(api.delete);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes?incluirInactivos=true`;

/** Baja del contrato: `DELETE /pacientes/{id}` (204 sin cuerpo). */
const RUTA_BAJA = (id: string) => `/pacientes/${id}`;

/** Fixture con la ficha en campos planos, la forma que devuelve `PacienteResponse` (S6). */
const PACIENTE_ACTIVO: Paciente = {
  id: 'pac-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Paciente de prueba',
  telefono: '5551234567',
  fechaNacimiento: '1990-05-04',
  sexo: 'F',
  email: 'paciente@correo.mx',
  notas: null,
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
  curp: 'LOFM900504MOCPZR08',
  ocupacion: 'Docente',
  domicilio: 'Calle Independencia 12, Centro',
  nombreResponsable: 'Responsable de prueba',
  telefonoResponsable: '5550001111',
  parentesco: 'Hermana',
};

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});

  useAuthStore.setState({
    accessToken: null,
    user: { id: 'usr-0001', email: 'admin@correo.mx', role: 'ADMIN', tenantId: ORGANIZACION_ID },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
});

describe('PacientesPage · baja de un paciente', () => {
  it('llama a la ruta exacta de baja y deja la fila inactiva con la opción de reactivar', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE_ACTIVO]));
    deleteMock.mockResolvedValue(respuestaCon<void>(undefined));

    renderConQueryClient(<PacientesPage />);

    await screen.findByText(PACIENTE_ACTIVO.nombre);
    expect(screen.getByText('Activo')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /desactivar/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1));
    // URL exacta del contrato: la colección anidada no sirve para la baja.
    expect(deleteMock.mock.calls[0][0]).toBe(RUTA_BAJA(PACIENTE_ACTIVO.id));

    // La fila pasa a inactiva y la baja queda reversible en el mismo lugar.
    await waitFor(() => expect(screen.getByText('Inactivo')).toBeInTheDocument());
    expect(screen.queryByText('Activo')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reactivar/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /desactivar/i })).not.toBeInTheDocument();

    expect(await screen.findByText(/paciente desactivado correctamente/i)).toBeInTheDocument();
    // Sin recarga del directorio: el estado local alcanza y la lista del servicio solo trae activos.
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith(RUTA_COLECCION);
  });

  it('muestra su propio mensaje de error y deja la fila activa cuando la baja falla', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE_ACTIVO]));
    deleteMock.mockRejectedValue(new Error('sin conexión'));

    renderConQueryClient(<PacientesPage />);

    await screen.findByText(PACIENTE_ACTIVO.nombre);

    fireEvent.click(screen.getByRole('button', { name: /desactivar/i }));

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/no fue posible desactivar al paciente/i)).toBeInTheDocument();
    // El fallo de la baja no se confunde con el de carga del directorio.
    expect(
      screen.queryByText(/no fue posible obtener el directorio de pacientes/i),
    ).not.toBeInTheDocument();
    // La fila conserva su estado real y la acción sigue disponible para reintentar.
    expect(screen.getByText('Activo')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /desactivar/i })).toBeInTheDocument();
  });
});
