import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S7 y S8 · la ficha administrativa que la pantalla pide es la que viaja al backend.
 *
 * Antes de este cambio el formulario enviaba un objeto anidado `ficha` que el backend no
 * declaraba: Spring lo descartaba en silencio, así que la recepcionista escribía los datos y la
 * tabla quedaba vacía sin ningún error. Aquí se fija el contrato plano (los seis campos al mismo
 * nivel que el resto, en camelCase), el parentesco —que no existía en el formulario—, la lectura
 * de la tabla y la búsqueda por CURP sin distinguir mayúsculas.
 *
 * Todo se comprueba por la interfaz pública (roles, texto y el cuerpo de la petición), nunca por
 * el estado interno.
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
const postMock = vi.mocked(api.post);
const putMock = vi.mocked(api.put);

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Organización de la sesión: es el claim `tenantId` y la dueña de la colección de pacientes. */
const ORGANIZACION_ID = 'org-001';

/** Colección del alta. */
const RUTA_COLECCION = `/organizaciones/${ORGANIZACION_ID}/pacientes`;

/** Ítem plano de la edición. */
const RUTA_ITEM = (id: string) => `/pacientes/${id}`;

/** CURP canónica de la fixture: única dentro de la organización, como exige S2. */
const CURP_CANONICA = 'SAGC880514MOCMRL09';

const PACIENTE: Paciente = {
  id: 'pac-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Paciente de prueba',
  telefono: '5551234567',
  fechaNacimiento: '1988-05-14',
  sexo: 'F',
  email: 'paciente@correo.mx',
  notas: null,
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
  curp: CURP_CANONICA,
  ocupacion: 'Comerciante',
  domicilio: 'Calle Independencia 12, Centro',
  nombreResponsable: 'Responsable de prueba',
  telefonoResponsable: '5550001111',
  parentesco: 'Hermana',
};

/** Segundo paciente para las pruebas de búsqueda: su CURP viene en minúsculas del backend viejo. */
const PACIENTE_CON_CURP_EN_MINUSCULAS: Paciente = {
  ...PACIENTE,
  id: 'pac-0002',
  nombre: 'Otro paciente de prueba',
  telefono: '5559876543',
  curp: 'pehj751122hocrn03',
  ocupacion: 'Contador',
  parentesco: 'Esposa',
  nombreResponsable: 'Otro responsable de prueba',
};

/** Mensaje de negocio que el backend adjunta al 409 (`ErrorResponse.mensaje`). */
const MENSAJE_DEL_BACKEND =
  'Ya existe un paciente con la CURP SAGC880514MOCMRL09 en esta organización. La CURP es única dentro de la organización, aunque el mismo paciente pueda estar registrado en otra';

/** Rechazo con la forma real del backend: `ErrorResponse` con `mensaje`. */
function rechazoPorCurpDuplicada() {
  return {
    response: {
      status: 409,
      data: { codigo: 409, error: 'Conflict', mensaje: MENSAJE_DEL_BACKEND },
    },
  };
}

beforeEach(() => {
  getMock.mockReset();
  postMock.mockReset();
  putMock.mockReset();
  // El fallo de guardado se registra en consola a propósito (resumen sin secretos).
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

/** Escribe en el campo del formulario localizado por su etiqueta visible. */
function escribir(etiqueta: RegExp, valor: string) {
  fireEvent.change(screen.getByLabelText(etiqueta), { target: { value: valor } });
}

/** Abre el formulario de alta sobre un directorio ya cargado. */
async function abrirAlta() {
  fireEvent.click(await screen.findByRole('button', { name: /nuevo paciente/i }));
}

/** Devuelve la fila de la tabla que contiene al paciente indicado. */
function filaDe(paciente: Paciente): HTMLElement {
  const fila = screen.getByText(paciente.nombre).closest('tr');
  if (!fila) throw new Error(`El paciente ${paciente.nombre} no está en la tabla`);
  return fila;
}

/** Abre la ficha del único paciente listado. */
async function abrirFichaDe(nombre: string) {
  await screen.findByText(nombre);
  fireEvent.click(screen.getByRole('button', { name: /ficha/i }));
}

/** Llena los seis campos de la ficha de identificación del formulario. */
function llenarFicha(valores: {
  curp?: string;
  ocupacion?: string;
  domicilio?: string;
  nombreResponsable?: string;
  telefonoResponsable?: string;
  parentesco?: string;
}) {
  if (valores.curp !== undefined) escribir(/^curp$/i, valores.curp);
  if (valores.ocupacion !== undefined) escribir(/ocupación/i, valores.ocupacion);
  if (valores.domicilio !== undefined) escribir(/domicilio completo/i, valores.domicilio);
  if (valores.nombreResponsable !== undefined)
    escribir(/nombre del responsable/i, valores.nombreResponsable);
  if (valores.telefonoResponsable !== undefined)
    escribir(/teléfono del responsable/i, valores.telefonoResponsable);
  if (valores.parentesco !== undefined) escribir(/parentesco/i, valores.parentesco);
}

describe('PacientesPage · el alta envía la ficha en campos planos (S8)', () => {
  it('manda los seis campos al mismo nivel que el resto y sin objeto anidado', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));
    postMock.mockResolvedValue(respuestaCon<Paciente>({ ...PACIENTE, id: 'pac-0009' }));

    render(<PacientesPage />);

    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/nombre completo/i, 'Paciente de prueba');
    // La CURP se escribe en minúsculas: el campo la pasa a mayúsculas, que es la forma canónica.
    llenarFicha({
      curp: CURP_CANONICA.toLowerCase(),
      ocupacion: 'Comerciante',
      domicilio: 'Calle Independencia 12, Centro',
      nombreResponsable: 'Responsable de prueba',
      telefonoResponsable: '5550001111',
      parentesco: 'Hermana',
    });

    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));

    const [ruta, cuerpo] = postMock.mock.calls[0];
    expect(ruta).toBe(RUTA_COLECCION);
    expect(cuerpo).toEqual({
      nombre: 'Paciente de prueba',
      telefono: null,
      fechaNacimiento: null,
      sexo: null,
      email: null,
      notas: null,
      curp: CURP_CANONICA,
      ocupacion: 'Comerciante',
      domicilio: 'Calle Independencia 12, Centro',
      nombreResponsable: 'Responsable de prueba',
      telefonoResponsable: '5550001111',
      parentesco: 'Hermana',
    });
    // El objeto anidado del contrato viejo deja de existir en el cliente.
    expect(cuerpo).not.toHaveProperty('ficha');
    expect(await screen.findByText(/paciente registrado correctamente/i)).toBeInTheDocument();
  });

  it('la edición también manda la ficha plana y actualiza la fila con lo que devolvió el backend', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));
    putMock.mockResolvedValue(
      respuestaCon<Paciente>({ ...PACIENTE, parentesco: 'Tutora legal' }),
    );

    render(<PacientesPage />);

    await abrirFichaDe(PACIENTE.nombre);

    // Los seis campos llegan al formulario desde los campos planos del paciente.
    expect(screen.getByLabelText(/^curp$/i)).toHaveValue(CURP_CANONICA);
    expect(screen.getByLabelText(/ocupación/i)).toHaveValue('Comerciante');
    expect(screen.getByLabelText(/domicilio completo/i)).toHaveValue('Calle Independencia 12, Centro');
    expect(screen.getByLabelText(/nombre del responsable/i)).toHaveValue('Responsable de prueba');
    expect(screen.getByLabelText(/teléfono del responsable/i)).toHaveValue('5550001111');
    expect(screen.getByLabelText(/parentesco/i)).toHaveValue('Hermana');

    llenarFicha({ parentesco: 'Tutora legal' });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1));
    const [ruta, cuerpo] = putMock.mock.calls[0];
    expect(ruta).toBe(RUTA_ITEM(PACIENTE.id));
    expect(cuerpo).toEqual({
      nombre: PACIENTE.nombre,
      telefono: PACIENTE.telefono,
      fechaNacimiento: PACIENTE.fechaNacimiento,
      sexo: PACIENTE.sexo,
      email: PACIENTE.email,
      notas: null,
      curp: PACIENTE.curp,
      ocupacion: PACIENTE.ocupacion,
      domicilio: PACIENTE.domicilio,
      nombreResponsable: PACIENTE.nombreResponsable,
      telefonoResponsable: PACIENTE.telefonoResponsable,
      parentesco: 'Tutora legal',
    });
    expect(cuerpo).not.toHaveProperty('ficha');

    // La tabla muestra el valor que devolvió el backend, no el que el usuario tecleó.
    expect(await screen.findByText('Tutora legal')).toBeInTheDocument();
  });
});

describe('PacientesPage · la tabla muestra la ficha que devolvió el backend (S7)', () => {
  it('lista CURP, responsable y parentesco tomados de los campos planos', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);
    expect(screen.getByText(CURP_CANONICA)).toBeInTheDocument();
    expect(screen.getByText('Responsable de prueba')).toBeInTheDocument();
    expect(screen.getByText('Hermana')).toBeInTheDocument();
  });

  it('deja «—» solo en las celdas de la ficha del paciente que no la tiene', async () => {
    // Un paciente con ficha completa y otro sin ninguno de sus datos, en la misma tabla: el guion
    // tiene que depender del dato de cada fila y no de lo que la tabla escriba siempre.
    const PACIENTE_SIN_FICHA: Paciente = {
      ...PACIENTE,
      id: 'pac-0003',
      nombre: 'Paciente sin ficha',
      curp: null,
      ocupacion: null,
      domicilio: null,
      nombreResponsable: null,
      telefonoResponsable: null,
      parentesco: null,
    };
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE, PACIENTE_SIN_FICHA]));

    render(<PacientesPage />);

    await screen.findByText(PACIENTE_SIN_FICHA.nombre);

    // En la fila del paciente sin ficha, la celda de la CURP (cuarta columna) y la del responsable
    // (quinta) dicen «—»: el dato ausente se declara en su propia celda.
    const celdas = within(filaDe(PACIENTE_SIN_FICHA)).getAllByRole('cell');
    expect(celdas[3]).toHaveTextContent(/^—$/);
    expect(celdas[4]).toHaveTextContent(/^—$/);
    expect(within(filaDe(PACIENTE_SIN_FICHA)).queryByText('Hermana')).not.toBeInTheDocument();

    // Y la fila del paciente que sí tiene ficha no muestra ningún guion: no es un texto fijo de
    // la tabla, es la marca de un dato que falta.
    expect(within(filaDe(PACIENTE)).queryByText('—')).not.toBeInTheDocument();
    expect(screen.getByText(CURP_CANONICA)).toBeInTheDocument();
  });
});

describe('PacientesPage · búsqueda por CURP sin distinguir mayúsculas (S7)', () => {
  it('encuentra la CURP canónica escribiéndola en minúsculas y al revés', async () => {
    getMock.mockResolvedValue(
      respuestaCon<Paciente[]>([PACIENTE, PACIENTE_CON_CURP_EN_MINUSCULAS]),
    );

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);
    await screen.findByText(PACIENTE_CON_CURP_EN_MINUSCULAS.nombre);

    const buscador = screen.getByPlaceholderText(/buscar por nombre/i);

    // El paciente guarda la CURP canónica y el usuario la escribe como se la dictaron.
    fireEvent.change(buscador, { target: { value: CURP_CANONICA.toLowerCase() } });
    expect(screen.getByText(PACIENTE.nombre)).toBeInTheDocument();
    expect(screen.queryByText(PACIENTE_CON_CURP_EN_MINUSCULAS.nombre)).not.toBeInTheDocument();

    // Y un registro con la CURP en minúsculas se encuentra con la forma canónica.
    fireEvent.change(buscador, { target: { value: 'PEHJ751122HOCRN03' } });
    expect(screen.getByText(PACIENTE_CON_CURP_EN_MINUSCULAS.nombre)).toBeInTheDocument();
    expect(screen.queryByText(PACIENTE.nombre)).not.toBeInTheDocument();
  });

  it('encuentra también por ocupación', async () => {
    getMock.mockResolvedValue(
      respuestaCon<Paciente[]>([PACIENTE, PACIENTE_CON_CURP_EN_MINUSCULAS]),
    );

    render(<PacientesPage />);

    await screen.findByText(PACIENTE.nombre);

    fireEvent.change(screen.getByPlaceholderText(/buscar por nombre/i), {
      target: { value: 'comerciante' },
    });

    expect(screen.getByText(PACIENTE.nombre)).toBeInTheDocument();
    expect(screen.queryByText(PACIENTE_CON_CURP_EN_MINUSCULAS.nombre)).not.toBeInTheDocument();
  });
});

describe('PacientesPage · rechazo por CURP duplicada (S7)', () => {
  it('muestra el mensaje del backend al dar de alta y no agrega la fila', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([]));
    postMock.mockRejectedValue(rechazoPorCurpDuplicada());

    render(<PacientesPage />);

    await screen.findByText(/aún no hay pacientes registrados/i);
    await abrirAlta();

    escribir(/nombre completo/i, 'Paciente de prueba');
    llenarFicha({ curp: CURP_CANONICA, parentesco: 'Hermana' });
    fireEvent.click(screen.getByRole('button', { name: /registrar paciente/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    // El motivo lo pone el backend: reemplazarlo por un texto genérico dejaría a la recepcionista
    // sin saber que la CURP ya existe.
    expect(await screen.findByText(MENSAJE_DEL_BACKEND)).toBeInTheDocument();
    expect(screen.getByText(/no se pudo guardar la ficha del paciente/i)).toBeInTheDocument();

    // El alta rechazada no crea la fila ni se anuncia como éxito.
    expect(screen.queryByText(/paciente registrado correctamente/i)).not.toBeInTheDocument();
    expect(screen.getByText(/aún no hay pacientes registrados/i)).toBeInTheDocument();
  });

  it('muestra el mensaje del backend al editar y conserva los datos reales de la fila', async () => {
    getMock.mockResolvedValue(respuestaCon<Paciente[]>([PACIENTE]));
    putMock.mockRejectedValue(rechazoPorCurpDuplicada());

    render(<PacientesPage />);

    await abrirFichaDe(PACIENTE.nombre);
    llenarFicha({ curp: 'PEHJ751122HOCRN03' });
    fireEvent.click(screen.getByRole('button', { name: /guardar cambios/i }));

    await waitFor(() => expect(putMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(MENSAJE_DEL_BACKEND)).toBeInTheDocument();

    // La fila conserva la CURP que el backend tiene guardada.
    expect(screen.queryByText('PEHJ751122HOCRN03')).not.toBeInTheDocument();
    expect(screen.getByText(CURP_CANONICA)).toBeInTheDocument();
    expect(screen.queryByText(/ficha del paciente actualizada correctamente/i)).not.toBeInTheDocument();
  });
});
