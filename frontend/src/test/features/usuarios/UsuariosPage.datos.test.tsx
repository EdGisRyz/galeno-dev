import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MENSAJE_NOMBRE_OBLIGATORIO } from '@/lib/patronesDeFormato';
import { UsuariosPage } from '@/features/usuarios/UsuariosPage';
import type { Usuario } from '@/features/usuarios/usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S1, S4 y S5 · el alta y la edición piden los datos de la persona, y la lista muestra personas.
 *
 * El alta pedía correo y rol solamente, así que la cuenta era un identificador y no alguien que
 * trabaja en la clínica: la tabla solo mostraba el correo. Aquí se fija lo que tiene que pasar:
 *
 * - el **nombre es obligatorio** y el **teléfono opcional**, con la regla compartida y el mensaje
 *   junto al campo, sin llamar a la API (S1, S7);
 * - la **tabla muestra el nombre** como dato principal y el correo como secundario (S4);
 * - las **cuentas anteriores, sin nombre, siguen mostrando el correo**: no se inventa un nombre a
 *   partir de la dirección —`pablo@correo.mx` no se convierte en «Pablo»— (S4);
 * - **editar** prellena los dos campos y **guardar una cuenta anterior exige completarle el
 *   nombre** (S5).
 *
 * Todo se comprueba por la interfaz pública: etiquetas, texto visible y el cuerpo de la petición.
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
const putMock = vi.mocked(api.put);

const ORGANIZACION_ID = 'org-001';
const RUTA_CUENTAS = `/organizaciones/${ORGANIZACION_ID}/usuarios`;
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;

const MENSAJE_TEXTO_SIN_DIGITOS = /solo admite letras, espacios, punto, guion y apóstrofo/i;
const MENSAJE_TELEFONO = /debe tener entre 7 y 20 caracteres/i;

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const CUENTA_PROPIA: Usuario = {
  id: 'usr-admin-1',
  organizacionId: ORGANIZACION_ID,
  email: 'admin@dev-auth.local',
  rol: 'ADMIN',
  activo: true,
  createdAt: '2026-01-01T10:00:00.000Z',
};

/** Cuenta con los dos datos de la persona: es la que dibuja la lista como persona. */
const CUENTA_CON_DATOS: Usuario = {
  id: 'usr-recepcion-1',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Recepción Matutina',
  telefono: '951-501-2001',
  email: 'recepcion@correo.mx',
  rol: 'RECEPCIONISTA',
  activo: true,
  createdAt: '2026-01-03T10:00:00.000Z',
};

/** Cuenta anterior a la V10: sin nombre ni teléfono. La lista la muestra por correo. */
const CUENTA_ANTERIOR: Usuario = {
  id: 'usr-anterior-1',
  organizacionId: ORGANIZACION_ID,
  nombre: null,
  telefono: null,
  email: 'pablo@correo.mx',
  rol: 'RECEPCIONISTA',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const MEDICO_ASOCIADO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: 'con-0001',
  nombre: 'Consultorio de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: null,
  tarifaBase: null,
  activo: true,
};

/** Directorio de cuentas que devuelve el servicio. */
function servirDirectorio(cuentas: Usuario[]) {
  getMock.mockImplementation(((url: string) =>
    Promise.resolve(
      url === RUTA_MEDICOS ? respuestaCon<Medico[]>([MEDICO_ASOCIADO]) : respuestaCon(cuentas),
    )) as typeof api.get);
}

/** Fila de la tabla que corresponde a la cuenta indicada, localizada por su correo. */
function filaDe(cuenta: Usuario): HTMLElement {
  const celda = screen.getByText(cuenta.email);
  const fila = celda.closest('tr');
  if (!fila) throw new Error(`La cuenta ${cuenta.email} no está en la tabla`);
  return fila;
}

/** Abre el formulario de alta sobre un directorio ya cargado. */
async function abrirAlta(cuentas: Usuario[] = [CUENTA_PROPIA]) {
  servirDirectorio(cuentas);
  render(<UsuariosPage />);
  await screen.findByText(cuentas[0].email);
  fireEvent.click(screen.getByRole('button', { name: /nueva cuenta/i }));
}

/** Abre el formulario de edición de la cuenta indicada. */
async function abrirEdicion(cuenta: Usuario, cuentas: Usuario[]) {
  servirDirectorio(cuentas);
  render(<UsuariosPage />);
  await screen.findByText(cuenta.email);
  fireEvent.click(within(filaDe(cuenta)).getByRole('button', { name: /editar/i }));
}

/** Escribe en un campo del modal localizado por su etiqueta visible. */
function escribir(etiqueta: RegExp, valor: string) {
  fireEvent.change(screen.getByLabelText(etiqueta), { target: { value: valor } });
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  putMock.mockReset();
  vi.mocked(api.delete).mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});

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

describe('UsuariosPage · el alta pide los datos de la persona (S1, S7)', () => {
  it('no envía el alta sin nombre y deja el mensaje junto al campo', async () => {
    await abrirAlta();

    escribir(/^correo/i, 'recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    expect(await screen.findByText(MENSAJE_NOMBRE_OBLIGATORIO)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
    // El formulario sigue abierto para corregir el dato sin volver a escribirlo todo.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('no envía el alta sin correo y deja el mensaje junto al campo', async () => {
    await abrirAlta();

    // El correo es el identificador de inicio de sesión: el formulario no se envía sin él y lo dice
    // con su propio mensaje, en español y junto al campo.
    escribir(/^nombre/i, 'Recepción Matutina');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    expect(await screen.findByText('El correo es obligatorio.')).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta con dígitos en el nombre: la regla es la de las personas', async () => {
    await abrirAlta();

    escribir(/^nombre/i, 'Administrador 123');
    escribir(/^correo/i, 'recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    expect(await screen.findByText(MENSAJE_TEXTO_SIN_DIGITOS)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('no envía el alta con un teléfono sin forma de teléfono', async () => {
    await abrirAlta();

    escribir(/^nombre/i, 'Recepción Matutina');
    escribir(/^teléfono/i, 'vvvvvuvu');
    escribir(/^correo/i, 'recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    expect(await screen.findByText(MENSAJE_TELEFONO)).toBeInTheDocument();
    expect(postMock).not.toHaveBeenCalled();
  });

  it('envía el nombre y el teléfono tal como se escribieron', async () => {
    await abrirAlta();
    postMock.mockResolvedValue(respuestaCon<Usuario>(CUENTA_CON_DATOS));

    escribir(/^nombre/i, 'Recepción Matutina');
    escribir(/^teléfono/i, '951-501-2001');
    escribir(/^correo/i, 'recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [ruta, cuerpo] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(ruta).toBe(RUTA_CUENTAS);
    expect(cuerpo.nombre).toBe('Recepción Matutina');
    expect(cuerpo.telefono).toBe('951-501-2001');
  });

  it('el teléfono es opcional: en blanco viaja como null y el alta se envía', async () => {
    await abrirAlta();
    postMock.mockResolvedValue(respuestaCon<Usuario>({ ...CUENTA_CON_DATOS, telefono: null }));

    escribir(/^nombre/i, 'Recepción Matutina');
    escribir(/^correo/i, 'recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [, cuerpo] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(cuerpo.nombre).toBe('Recepción Matutina');
    expect(cuerpo.telefono).toBeNull();
  });
});

describe('UsuariosPage · la lista muestra personas (S4)', () => {
  it('muestra el nombre como dato principal y el correo como secundario', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_CON_DATOS]);

    render(<UsuariosPage />);
    await screen.findByText(CUENTA_CON_DATOS.email);

    const fila = filaDe(CUENTA_CON_DATOS);
    expect(within(fila).getByText('Recepción Matutina')).toBeInTheDocument();
    expect(within(fila).getByText(CUENTA_CON_DATOS.email)).toBeInTheDocument();
  });

  it('una cuenta anterior sin nombre sigue mostrando el correo, sin inventarlo desde la dirección', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_ANTERIOR]);

    render(<UsuariosPage />);
    await screen.findByText(CUENTA_ANTERIOR.email);

    expect(filaDe(CUENTA_ANTERIOR)).toBeInTheDocument();
    // `pablo@correo.mx` no se convierte en «Pablo» ni en «Pablo correo».
    expect(screen.queryByText('Pablo')).not.toBeInTheDocument();
    expect(screen.queryByText(/^pablo$/i)).not.toBeInTheDocument();
  });

  it('la búsqueda sigue encontrando por correo y además por nombre', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_CON_DATOS]);

    render(<UsuariosPage />);
    await screen.findByText(CUENTA_CON_DATOS.email);

    const buscador = screen.getByPlaceholderText(/buscar por/i);

    fireEvent.change(buscador, { target: { value: 'recepcion@correo.mx' } });
    expect(filaDe(CUENTA_CON_DATOS)).toBeInTheDocument();
    expect(screen.queryByText(CUENTA_PROPIA.email)).not.toBeInTheDocument();

    fireEvent.change(buscador, { target: { value: 'matutina' } });
    expect(filaDe(CUENTA_CON_DATOS)).toBeInTheDocument();
  });
});

describe('UsuariosPage · la edición corrige nombre y teléfono (S5)', () => {
  it('prellena los dos campos con lo que devolvió el servicio y los envía al guardar', async () => {
    await abrirEdicion(CUENTA_CON_DATOS, [CUENTA_PROPIA, CUENTA_CON_DATOS]);
    putMock.mockResolvedValue(
      respuestaCon<Usuario>({ ...CUENTA_CON_DATOS, nombre: 'Recepción Vespertina' }),
    );

    const campoNombre = screen.getByLabelText(/^nombre/i) as HTMLInputElement;
    const campoTelefono = screen.getByLabelText(/^teléfono/i) as HTMLInputElement;
    expect(campoNombre.value).toBe('Recepción Matutina');
    expect(campoTelefono.value).toBe('951-501-2001');

    escribir(/^nombre/i, 'Recepción Vespertina');
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1));
    const [ruta, cuerpo] = putMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(ruta).toBe(`/usuarios/${CUENTA_CON_DATOS.id}`);
    expect(cuerpo.nombre).toBe('Recepción Vespertina');
    expect(cuerpo.telefono).toBe('951-501-2001');
  });

  it('guardar una cuenta anterior obliga a completarle el nombre', async () => {
    await abrirEdicion(CUENTA_ANTERIOR, [CUENTA_PROPIA, CUENTA_ANTERIOR]);

    expect((screen.getByLabelText(/^nombre/i) as HTMLInputElement).value).toBe('');
    escribir(/^correo/i, 'pablo@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    expect(await screen.findByText(MENSAJE_NOMBRE_OBLIGATORIO)).toBeInTheDocument();
    expect(putMock).not.toHaveBeenCalled();
  });
});
