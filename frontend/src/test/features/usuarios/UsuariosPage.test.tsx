import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { UsuariosPage } from '@/features/usuarios/UsuariosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Usuario } from '@/features/usuarios/usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * S6 · la pantalla de cuentas ofrece tres roles y trata las bajas que el backend rechaza.
 *
 * La historia de usuario nombra administrador, recepcionista y médico: `PACIENTE` no se ofrece
 * porque no tiene ninguna celda en la matriz y la cuenta quedaría creada sin poder hacer nada. El
 * tipo `RolUsuario` conserva los cuatro valores —la base y las respuestas del backend los
 * admiten—, así que lo que cambia es lo que el selector **ofrece**.
 *
 * Las dos reglas de baja (`403` sobre la propia cuenta, `409` sobre la última cuenta ADMIN
 * activa) las decide el backend; la pantalla solo puede mostrar su rechazo. Lo que se fija aquí
 * es que ese rechazo se vea y que la fila **siga** en la lista: nunca se saca una cuenta que el
 * servicio no dio de baja.
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

/** Rechazo HTTP con la forma que consume `resumenDeErrorHttp` (estado, método y ruta). */
function rechazoHttp(estado: number, url: string): Error {
  return Object.assign(new Error(`Request failed with status code ${estado}`), {
    config: { method: 'delete', url },
    response: { status: estado },
  });
}

const ORGANIZACION_ID = 'org-001';
const RUTA_CUENTAS = `/organizaciones/${ORGANIZACION_ID}/usuarios`;
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos`;
const RUTA_BAJA = (id: string) => `/usuarios/${id}`;

const MENSAJE_ERROR_BAJA =
  /no fue posible dar de baja la cuenta\. una cuenta no puede darse de baja a sí misma, y la organización necesita conservar al menos una cuenta admin activa\./i;

const CUENTA_PROPIA: Usuario = {
  id: 'usr-admin-1',
  organizacionId: ORGANIZACION_ID,
  email: 'admin@correo.mx',
  rol: 'ADMIN',
  activo: true,
  createdAt: '2026-01-01T10:00:00.000Z',
};

const CUENTA_ADMINISTRADORA: Usuario = {
  id: 'usr-admin-2',
  organizacionId: ORGANIZACION_ID,
  email: 'encargada@correo.mx',
  rol: 'ADMIN',
  activo: true,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const CUENTA_DE_RECEPCION: Usuario = {
  id: 'usr-recepcion-1',
  organizacionId: ORGANIZACION_ID,
  email: 'recepcion@correo.mx',
  rol: 'RECEPCIONISTA',
  activo: true,
  createdAt: '2026-01-03T10:00:00.000Z',
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

/** Directorio de cuentas que devuelve el servicio y médico asociado del formulario. */
function servirDirectorio(cuentas: Usuario[]) {
  getMock.mockImplementation(((url: string) =>
    Promise.resolve(
      url === RUTA_MEDICOS ? respuestaCon<Medico[]>([MEDICO_ASOCIADO]) : respuestaCon(cuentas),
    )) as typeof api.get);
}

/** Fila de la tabla que corresponde a la cuenta indicada. */
function filaDe(cuenta: Usuario): HTMLElement {
  const celda = screen.getByText(cuenta.email);
  const fila = celda.closest('tr');
  if (!fila) throw new Error(`La cuenta ${cuenta.email} no está en la tabla`);
  return fila;
}

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  vi.mocked(api.put).mockReset();
  deleteMock.mockReset();
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

describe('UsuariosPage · selector de rol (S6)', () => {
  it('ofrece exactamente tres roles —administrador, médico y recepción— y no ofrece PACIENTE', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_ADMINISTRADORA]);

    renderConQueryClient(<UsuariosPage />);
    await screen.findByText(CUENTA_ADMINISTRADORA.email);

    // El directorio se pide a la organización de la sesión, no a una ruta adivinada.
    expect(getMock).toHaveBeenCalledWith(RUTA_CUENTAS);

    fireEvent.click(screen.getByRole('button', { name: /nueva cuenta/i }));

    const selector = await screen.findByLabelText('Rol');
    const opciones = within(selector).getAllByRole('option');

    // Tres opciones, ni una más: `PACIENTE` no tiene celdas en la matriz y no debe ofrecerse.
    expect(opciones).toHaveLength(3);
    expect(opciones.map((opcion) => opcion.getAttribute('value'))).toEqual([
      'ADMIN',
      'MEDICO',
      'RECEPCIONISTA',
    ]);
    expect(opciones.map((opcion) => opcion.textContent)).toEqual([
      'ADMIN',
      'MEDICO',
      'RECEPCIONISTA',
    ]);
    expect(within(selector).queryByRole('option', { name: 'PACIENTE' })).not.toBeInTheDocument();

    // El valor por defecto del formulario sigue siendo un rol ofrecido.
    expect((selector as HTMLSelectElement).value).toBe('RECEPCIONISTA');
  });
});

describe('UsuariosPage · bajas rechazadas por el backend', () => {
  it('la propia cuenta no ofrece la baja: la pantalla no se da de baja a sí misma', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_ADMINISTRADORA]);

    renderConQueryClient(<UsuariosPage />);
    await screen.findByText(CUENTA_ADMINISTRADORA.email);

    const filaPropia = filaDe(CUENTA_PROPIA);
    expect(within(filaPropia).getByText('Tu cuenta')).toBeInTheDocument();
    expect(within(filaPropia).queryByRole('button', { name: /baja/i })).not.toBeInTheDocument();

    // La acción sigue disponible sobre las demás cuentas: la guarda es solo sobre la propia.
    expect(
      within(filaDe(CUENTA_ADMINISTRADORA)).getByRole('button', { name: /baja/i }),
    ).toBeInTheDocument();
  });

  it('una baja que el backend rechaza con 403 —darse de baja a sí mismo— muestra su mensaje y conserva la fila', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_DE_RECEPCION]);
    deleteMock.mockRejectedValue(rechazoHttp(403, RUTA_BAJA(CUENTA_DE_RECEPCION.id)));

    renderConQueryClient(<UsuariosPage />);
    await screen.findByText(CUENTA_DE_RECEPCION.email);

    fireEvent.click(
      within(filaDe(CUENTA_DE_RECEPCION)).getByRole('button', { name: /baja/i }),
    );
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: /dar de baja/i }),
    );

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1));
    expect(deleteMock.mock.calls[0][0]).toBe(RUTA_BAJA(CUENTA_DE_RECEPCION.id));

    // El rechazo se ve, con el mensaje que nombra las dos reglas del backend.
    expect(await screen.findByText(MENSAJE_ERROR_BAJA)).toBeInTheDocument();
    // Y la fila sigue activa en la lista: no se saca una cuenta que el servicio no dio de baja.
    expect(filaDe(CUENTA_DE_RECEPCION)).toBeInTheDocument();
    expect(within(filaDe(CUENTA_DE_RECEPCION)).getByText('Activa')).toBeInTheDocument();
    expect(screen.queryByText(/cuenta dada de baja correctamente/i)).not.toBeInTheDocument();
  });

  it('la baja de la última administradora, rechazada con 409, muestra su mensaje y conserva la fila', async () => {
    servirDirectorio([CUENTA_PROPIA, CUENTA_ADMINISTRADORA]);
    deleteMock.mockRejectedValue(rechazoHttp(409, RUTA_BAJA(CUENTA_ADMINISTRADORA.id)));

    renderConQueryClient(<UsuariosPage />);
    await screen.findByText(CUENTA_ADMINISTRADORA.email);

    fireEvent.click(
      within(filaDe(CUENTA_ADMINISTRADORA)).getByRole('button', { name: /baja/i }),
    );
    fireEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: /dar de baja/i }),
    );

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(MENSAJE_ERROR_BAJA)).toBeInTheDocument();

    // La última administradora sigue en la lista, con su rol y su estado intactos.
    expect(within(filaDe(CUENTA_ADMINISTRADORA)).getByText('ADMIN')).toBeInTheDocument();
    expect(within(filaDe(CUENTA_ADMINISTRADORA)).getByText('Activa')).toBeInTheDocument();
    expect(screen.queryByText(/cuenta dada de baja correctamente/i)).not.toBeInTheDocument();
  });
});
