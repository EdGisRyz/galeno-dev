import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { UsuariosPage } from '@/features/usuarios/UsuariosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Usuario } from '@/features/usuarios/usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S4 · la pantalla de cuentas valida la forma del correo antes de enviar.
 *
 * El correo de una cuenta es el identificador de inicio de sesión: una cuenta creada con `juan@`
 * nace sin que nadie pueda entrar con ella. Por eso la pantalla aplica la misma regla estructural
 * del backend —y la misma lista de casos válidos, que incluye los correos reales de la base y de
 * la semilla— **antes** de la petición, deja el mensaje junto al campo del correo y no llama a la
 * API. Si el backend rechaza igual, su mensaje se muestra tal cual.
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

const MENSAJE_CORREO = /el correo debe tener forma de correo/i;

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

/** Rechazo HTTP con la forma que consumen `resumenDeErrorHttp` y `mensajeDelBackend`. */
function rechazoConMensaje(estado: number, mensaje: string): Error {
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

const MEDICO_ASOCIADO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: 'con-0001',
  nombre: 'Medico de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: null,
  tarifaBase: null,
  activo: true,
};

/**
 * Correos sin forma de correo: la cuenta quedaría creada sin que nadie pueda iniciar sesión con
 * ella. Son los mismos casos que rechaza `UsuarioRequest` en el backend.
 */
const CORREOS_INVALIDOS = [
  'juan@',
  'juan',
  '@dominio.com',
  'juan@dominio',
  'juan @dominio.com',
  'juan@@dominio.com',
  'juan@.com',
];

/**
 * Correos con forma válida, incluidos los que ya existen en la base y en la semilla del emisor
 * —el dominio `dev-auth.local` es sintético y aun así tiene forma de correo— y uno con mayúsculas,
 * porque el backend normaliza a minúsculas después de validar.
 */
const CORREOS_VALIDOS = [
  'admin@dev-auth.local',
  'medico@dev-auth.local',
  'recepcion@dev-auth.local',
  'paciente@dev-auth.local',
  'ejemplo@gmail.com',
  'nombre+etiqueta@dominio.com.mx',
  'Recepcion@Clinica.MX',
];

/** Directorio cargado y, si hace falta, el médico que el formulario ofrece. */
function servirDirectorio(cuentas: Usuario[]) {
  getMock.mockImplementation(((url: string) =>
    Promise.resolve(
      url === RUTA_MEDICOS ? respuestaCon<Medico[]>([MEDICO_ASOCIADO]) : respuestaCon(cuentas),
    )) as typeof api.get);
}

/** Abre el formulario de alta de una cuenta sobre un directorio ya cargado. */
async function abrirAlta() {
  renderConQueryClient(<UsuariosPage />);
  await screen.findByText(CUENTA_PROPIA.email);
  fireEvent.click(screen.getByRole('button', { name: /nueva cuenta/i }));
  // El nombre es obligatorio desde S1: estos casos son de la forma del correo, así que se escribe
  // uno válido y la única regla que puede rechazar el formulario es la del correo.
  fireEvent.change(screen.getByLabelText(/^nombre/i), {
    target: { value: 'Recepción Matutina' },
  });
}

/** Escribe el correo en el campo del formulario, localizado por su etiqueta visible. */
function escribirCorreo(valor: string) {
  fireEvent.change(screen.getByLabelText(/^correo/i), { target: { value: valor } });
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

describe('UsuariosPage · forma del correo de la cuenta (S4)', () => {
  it.each(CORREOS_INVALIDOS)(
    'no envía el alta con el correo «%s» y muestra el mensaje junto al campo',
    async (correo) => {
      servirDirectorio([CUENTA_PROPIA]);
      await abrirAlta();

      escribirCorreo(correo);
      fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

      expect(await screen.findByText(MENSAJE_CORREO)).toBeInTheDocument();
      expect(postMock).not.toHaveBeenCalled();
      // El formulario sigue abierto para corregir el dato sin volver a escribirlo todo.
      expect(screen.getByRole('dialog')).toBeInTheDocument();
    },
  );

  it.each(CORREOS_VALIDOS)('sí envía el alta con el correo «%s» tal como se escribió', async (correo) => {
    servirDirectorio([CUENTA_PROPIA]);
    postMock.mockResolvedValue(respuestaCon<Usuario>({ ...CUENTA_PROPIA, email: correo }));
    await abrirAlta();

    escribirCorreo(correo);
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [ruta, cuerpo] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(ruta).toBe(RUTA_CUENTAS);
    // El correo viaja tal como se escribió: la normalización a minúsculas es del backend.
    expect(cuerpo.email).toBe(correo);
    expect(screen.queryByText(MENSAJE_CORREO)).not.toBeInTheDocument();
  });

  it('recorta los espacios de los extremos antes de validar y envía el correo recortado', async () => {
    servirDirectorio([CUENTA_PROPIA]);
    postMock.mockResolvedValue(
      respuestaCon<Usuario>({ ...CUENTA_PROPIA, email: 'juan@dominio.com' }),
    );
    await abrirAlta();

    // El caso de la prueba en vivo: un correo válido con un espacio invisible al pegar. Es el
    // mismo correo, así que se valida y se envía recortado (S6).
    escribirCorreo('  juan@dominio.com  ');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    const [, cuerpo] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(cuerpo.email).toBe('juan@dominio.com');
    expect(screen.queryByText(MENSAJE_CORREO)).not.toBeInTheDocument();
  });

  it('el rechazo del backend se sigue mostrando con su propio mensaje', async () => {
    servirDirectorio([CUENTA_PROPIA]);
    postMock.mockRejectedValue(
      rechazoConMensaje(409, 'El correo ya está en uso dentro de la organización: recepcion@correo.mx'),
    );
    await abrirAlta();

    escribirCorreo('recepcion@correo.mx');
    fireEvent.click(screen.getByRole('button', { name: /registrar cuenta/i }));

    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/el correo ya está en uso dentro de la organización/i)).toBeInTheDocument();
  });
});
