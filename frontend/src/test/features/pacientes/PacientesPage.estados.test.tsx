import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';

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
  // El store conecta el cliente HTTP al cargarse; aquí no hay red que conectar.
  conectarSesionHttp: vi.fn(),
}));

const getMock = vi.mocked(api.get);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/**
 * Organización de la sesión: es el claim `tenantId` y la dueña de la colección de pacientes. La
 * pantalla no puede inventar un tenant, así que la URL sale de aquí.
 */
const ORGANIZACION_ID = 'org-001';

/** Colección del contrato anidado: `GET/POST /organizaciones/{organizacionId}/pacientes`. */
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes`;

/** Ítem del contrato plano: `PUT /pacientes/{id}`. */
const RUTA_ITEM = (id: string) => `/pacientes/${id}`;

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

  // Sesión autenticada: la organización dueña del directorio sale de su claim `tenantId`.
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
    expect(getMock).toHaveBeenCalledWith(RUTA_COLECCION);

    fireEvent.click(reintentar);

    await waitFor(() => expect(getMock).toHaveBeenCalledTimes(2));
    expect(getMock).toHaveBeenLastCalledWith(RUTA_COLECCION);

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

describe('PacientesPage · rutas del contrato del backend (colección anidada, ítem plano)', () => {
  it('lista los pacientes en la colección de la organización de la sesión', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);

    // Una sola petición y a la ruta anidada: la colección plana ya no existe en el backend.
    expect(getMock).toHaveBeenCalledTimes(1);
    expect(getMock).toHaveBeenCalledWith(RUTA_COLECCION);
  });

  it('da de alta al paciente en la colección de la organización de la sesión', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));
    vi.mocked(api.post).mockResolvedValue(
      respuestaCon<Paciente>({ ...PACIENTE, id: 'pac-0002', nombre: 'Ana Ruiz' }),
    );

    render(<PacientesPage />);

    await screen.findByText(/aún no hay pacientes registrados/i);

    fireEvent.click(screen.getByRole('button', { name: /nuevo paciente/i }));
    fireEvent.change(screen.getByLabelText(/nombre completo/i), {
      target: { value: 'Ana Ruiz' },
    });
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.post).mock.calls[0][0]).toBe(RUTA_COLECCION);
    expect(await screen.findByText(/paciente registrado correctamente/i)).toBeInTheDocument();
  });

  it('actualiza la ficha en la ruta plana del paciente', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));
    vi.mocked(api.put).mockResolvedValue(
      respuestaCon<Paciente>({ ...PACIENTE, nombre: 'María F. López' }),
    );

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);

    fireEvent.click(screen.getByRole('button', { name: /ficha/i }));
    fireEvent.change(screen.getByLabelText(/nombre completo/i), {
      target: { value: 'María F. López' },
    });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(1));
    expect(vi.mocked(api.put).mock.calls[0][0]).toBe(RUTA_ITEM(PACIENTE.id));
  });

  it('no pide el directorio si la sesión no trae organización', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    useAuthStore.setState({
      accessToken: null,
      user: null,
      isAuthenticated: false,
      tenantSlug: '',
      inicializando: false,
    });

    render(<PacientesPage />);

    // Fail-closed: sin claim de tenant no hay organización y no se inventa una por defecto.
    expect(await screen.findByText(/no se pudieron cargar los pacientes/i)).toBeInTheDocument();
    expect(getMock).not.toHaveBeenCalled();
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
