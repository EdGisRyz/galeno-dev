import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import type { Paciente } from '@/features/pacientes/pacientes.types';

/**
 * F006 · estados de la pantalla de Pacientes: cargando, vacío y error.
 *
 * Antes de este cambio, un fallo al pedir `/pacientes` solo dejaba un `console.error` y la
 * pantalla se veía vacía, indistinguible de un directorio sin pacientes. Con `VITE_USE_MOCKS=false`
 * esa es justo la situación real (el backend todavía no expone `/pacientes`), así que el usuario
 * tiene que poder distinguir «no hay pacientes» de «no se pudo cargar» y reintentar.
 *
 * Todo se comprueba por la interfaz pública (roles y texto), nunca por el estado interno.
 */

vi.mock('@/lib/axios', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const getMock = vi.mocked(api.get);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const PACIENTE: Paciente = {
  id: 'pac-0001',
  organizacion_id: 'org-001',
  nombre: 'María Fernanda López',
  telefono: '5551234567',
  fecha_nacimiento: '1990-05-04',
  sexo: 'F',
  email: 'maria@correo.mx',
  notas: null,
  activo: true,
  created_at: '2026-01-02T10:00:00.000Z',
};

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  vi.mocked(api.put).mockReset();
});

describe('PacientesPage · estado de error (F006)', () => {
  beforeEach(() => {
    // El fallo se registra en consola a propósito (resumen sin secretos); aquí solo se silencia
    // la salida para no ensuciar el informe del runner.
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('muestra un mensaje de error visible cuando la petición falla', async () => {
    getMock.mockRejectedValue(new Error('sin conexión'));

    render(<PacientesPage />);

    expect(await screen.findByText(/no se pudieron cargar los pacientes/i)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(
      /no fue posible obtener el directorio de pacientes/i,
    );
    // El fallo ya no se anuncia como un directorio vacío.
    expect(screen.queryByText(/aún no hay pacientes registrados/i)).not.toBeInTheDocument();
  });

  it('no afirma métricas en cero cuando el directorio no se pudo leer', async () => {
    getMock.mockRejectedValue(new Error('sin conexión'));

    render(<PacientesPage />);

    await screen.findByRole('alert');

    // Un dato que no se pudo leer no es un cero: se muestra como desconocido.
    expect(screen.queryByText(/^0$/)).not.toBeInTheDocument();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1);
    // Y tampoco se anuncia un total que no se conoce.
    expect(screen.queryByText(/mostrando/i)).not.toBeInTheDocument();
  });

  it('el control de reintento vuelve a pedir los pacientes y limpia el error', async () => {
    getMock
      .mockRejectedValueOnce(new Error('sin conexión'))
      .mockResolvedValueOnce(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);

    const reintentar = await screen.findByRole('button', { name: /reintentar/i });
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith('/pacientes');

    fireEvent.click(reintentar);

    await waitFor(() => expect(getMock).toHaveBeenCalledTimes(2));
    expect(getMock).toHaveBeenLastCalledWith('/pacientes');

    await waitFor(() =>
      expect(screen.queryByText(/no se pudieron cargar los pacientes/i)).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/aún no hay pacientes registrados/i)).toBeInTheDocument();
  });

  it('muestra el estado de carga mientras el reintento está en curso', async () => {
    getMock.mockRejectedValueOnce(new Error('sin conexión'));

    render(<PacientesPage />);

    const reintentar = await screen.findByRole('button', { name: /reintentar/i });

    let resolver!: (valor: AxiosResponse<Paciente[]>) => void;
    const pendiente = new Promise<AxiosResponse<Paciente[]>>((res) => {
      resolver = res;
    });
    getMock.mockReturnValueOnce(pendiente as unknown as ReturnType<typeof api.get>);

    fireEvent.click(reintentar);

    expect(await screen.findByRole('status')).toHaveTextContent(/cargando pacientes/i);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    resolver(respuestaCon<Paciente[]>([]));
    await waitFor(() =>
      expect(screen.queryByRole('status')).not.toBeInTheDocument(),
    );
  });
});

describe('PacientesPage · estado de carga (F006)', () => {
  it('anuncia la carga en curso mientras la petición está pendiente', async () => {
    let resolver!: (valor: AxiosResponse<Paciente[]>) => void;
    const pendiente = new Promise<AxiosResponse<Paciente[]>>((res) => {
      resolver = res;
    });
    getMock.mockReturnValueOnce(pendiente as unknown as ReturnType<typeof api.get>);

    render(<PacientesPage />);

    const carga = screen.getByRole('status');
    expect(carga).toHaveTextContent(/cargando pacientes/i);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    resolver(respuestaCon<Paciente[]>([PACIENTE]));

    expect(await screen.findByText(PACIENTE.nombre)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

describe('PacientesPage · estado vacío (F006)', () => {
  it('distingue «sin pacientes registrados» de un fallo de carga', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));

    render(<PacientesPage />);

    expect(await screen.findByText(/aún no hay pacientes registrados/i)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/cargando pacientes/i)).not.toBeInTheDocument();
  });
});

describe('PacientesPage · comportamiento previo que se conserva', () => {
  it('renderiza la fila del paciente cuando la carga tiene éxito', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));

    render(<PacientesPage />);

    expect(await screen.findByText(PACIENTE.nombre)).toBeInTheDocument();
    expect(screen.getByText('5551234567')).toBeInTheDocument();
    expect(screen.queryByText(/aún no hay pacientes registrados/i)).not.toBeInTheDocument();
  });

  it('mantiene el mensaje de búsqueda sin resultados', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);

    fireEvent.change(screen.getByPlaceholderText(/buscar por nombre, teléfono o curp/i), {
      target: { value: 'zzz-no-existe' },
    });

    expect(
      screen.getByText(/no se encontraron pacientes con ese criterio/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/aún no hay pacientes registrados/i)).not.toBeInTheDocument();
  });
});
