import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Reactivación de un paciente dado de baja (spec S5): `POST /pacientes/{id}/activar` responde 204
 * sin cuerpo, así que la fila se marca activa en el estado local en lugar de recargar la lista.
 *
 * Se comprueba por la interfaz pública (roles y texto) y se afirma la URL exacta: una ruta
 * aproximada deja el contrato del backend sin cubrir.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
  // El store conecta el cliente HTTP al cargarse; aquí no hay red que conectar.
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Organización de la sesión: es el claim `tenantId` y la dueña de la colección de pacientes. */
const ORGANIZACION_ID = 'org-001';

/** Colección del contrato anidado: `GET /organizaciones/{organizacionId}/pacientes`. */
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes?incluirInactivos=true`;

/** Reactivación del contrato: `POST /pacientes/{id}/activar` (204 idempotente, sin cuerpo). */
const RUTA_ACTIVAR = (id: string) => `/pacientes/${id}/activar`;

/** Fixture con la ficha en campos planos, la forma que devuelve `PacienteResponse` (S6). */
const PACIENTE_INACTIVO: Paciente = {
  id: 'pac-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Paciente de prueba',
  telefono: '5551234567',
  fechaNacimiento: '1990-05-04',
  sexo: 'F',
  email: 'paciente@correo.mx',
  notas: null,
  activo: false,
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
  // Los fallos se registran en consola a propósito (resumen sin secretos); aquí solo se silencia
  // la salida para no ensuciar el informe del runner.
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

describe('PacientesPage · reactivación de un paciente inactivo (S5)', () => {
  it('llama a la ruta de activación y muestra la fila como activa sin recargar', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE_INACTIVO]));
    vi.mocked(api.post).mockResolvedValue(respuestaCon<void>(undefined));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE_INACTIVO.nombre);
    expect(screen.getByText('Inactivo')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    // URL exacta del contrato: ni la colección anidada ni una variante aproximada.
    expect(vi.mocked(api.post).mock.calls[0][0]).toBe(RUTA_ACTIVAR(PACIENTE_INACTIVO.id));

    // La fila pasa a activa en el estado local y el botón deja de ofrecerse.
    await waitFor(() => expect(screen.getByText('Activo')).toBeInTheDocument());
    expect(screen.queryByText('Inactivo')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reactivar/i })).not.toBeInTheDocument();

    // El aviso de éxito es el que ya usa la pantalla; no hubo recarga del directorio.
    expect(await screen.findByText(/paciente reactivado correctamente/i)).toBeInTheDocument();
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith(RUTA_COLECCION);
  });

  it('muestra su propio mensaje de error y deja la fila inactiva cuando la reactivación falla', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE_INACTIVO]));
    vi.mocked(api.post).mockRejectedValue(new Error('sin conexión'));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE_INACTIVO.nombre);

    fireEvent.click(screen.getByRole('button', { name: /reactivar/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/no fue posible reactivar al paciente/i)).toBeInTheDocument();
    // El fallo al reactivar no se confunde con el de carga del directorio.
    expect(
      screen.queryByText(/no fue posible obtener el directorio de pacientes/i),
    ).not.toBeInTheDocument();
    // La fila conserva su estado real y el control sigue disponible para reintentar.
    expect(screen.getByText('Inactivo')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reactivar/i })).toBeInTheDocument();
  });
});
