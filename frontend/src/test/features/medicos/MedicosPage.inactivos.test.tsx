import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { MedicosPage } from '@/features/medicos/MedicosPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';

/**
 * Estados inactivos en el directorio de médicos: la columna de consultorio no se vacía porque el
 * consultorio esté dado de baja (S12) y un médico inactivo no ofrece «Horarios» (S10).
 *
 * El mock acepta el listado de consultorios con o sin `incluirInactivos`, para que el rojo y el
 * verde midan el comportamiento de la pantalla y no la forma de la URL; una aserción aparte fija
 * que el listado se pide con las bajas.
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

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

const ORGANIZACION_ID = 'org-001';
const RUTA_MEDICOS = `/organizaciones/${ORGANIZACION_ID}/medicos?incluirInactivos=true`;
const RUTA_CONSULTORIOS = `/organizaciones/${ORGANIZACION_ID}/consultorios`;
const RUTA_LISTADO_CONSULTORIOS = `${RUTA_CONSULTORIOS}?incluirInactivos=true`;

const CONSULTORIO_NORTE: Consultorio = {
  id: 'con-norte',
  organizacionId: ORGANIZACION_ID,
  nombre: 'Consultorio Norte',
  direccion: 'Calle Norte 1',
  telefono: null,
  activo: false,
  createdAt: '2026-01-02T10:00:00.000Z',
};

const CONSULTORIO_CENTRO: Consultorio = {
  ...CONSULTORIO_NORTE,
  id: 'con-centro',
  nombre: 'Consultorio Centro',
  activo: true,
};

const MEDICO_ACTIVO: Medico = {
  id: 'med-0001',
  organizacionId: ORGANIZACION_ID,
  consultorioId: CONSULTORIO_NORTE.id,
  nombre: 'Médica de prueba',
  especialidad: 'General',
  cedula: '1234567',
  telefono: null,
  tarifaBase: 500,
  activo: true,
};

const MEDICO_INACTIVO: Medico = {
  ...MEDICO_ACTIVO,
  id: 'med-0002',
  nombre: 'Médico inactivo de prueba',
  consultorioId: CONSULTORIO_CENTRO.id,
  activo: false,
};

function cargarCon(medicos: Medico[], consultorios: Consultorio[]) {
  getMock.mockImplementation((url: string) => {
    if (url === RUTA_MEDICOS) return Promise.resolve(respuestaCon(medicos));
    // La pantalla puede pedir el listado con o sin el parámetro: lo que se mide es la tabla.
    if (url.startsWith(RUTA_CONSULTORIOS)) return Promise.resolve(respuestaCon(consultorios));
    return Promise.reject(new Error(`URL inesperada: ${url}`));
  });
}

function filaDe(medico: Medico): HTMLElement {
  const fila = screen.getByText(medico.nombre).closest('tr');
  if (!fila) throw new Error(`El médico ${medico.nombre} no está en la tabla`);
  return fila;
}

/**
 * Celda de consultorio de la fila: es la segunda columna de la tabla (médico, consultorio, ...).
 * Se mira la celda y no el texto suelto porque la de contacto usa «—» para los campos vacíos.
 */
function celdaDeConsultorio(medico: Medico): HTMLElement {
  const celdas = within(filaDe(medico)).getAllByRole('cell');
  return celdas[1];
}

beforeEach(() => {
  getMock.mockReset();
  vi.mocked(api.post).mockReset();
  vi.mocked(api.put).mockReset();
  vi.mocked(api.delete).mockReset();
  useAuthStore.setState({
    accessToken: null,
    user: { id: 'usr-0001', email: 'admin@correo.mx', role: 'ADMIN', tenantId: ORGANIZACION_ID },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('MedicosPage · estados inactivos del directorio', () => {
  it('muestra el nombre del consultorio del médico aunque esté dado de baja (S12)', async () => {
    cargarCon([MEDICO_ACTIVO], [CONSULTORIO_NORTE, CONSULTORIO_CENTRO]);

    renderConQueryClient(<MedicosPage />);

    await screen.findByText(MEDICO_ACTIVO.nombre);
    expect(celdaDeConsultorio(MEDICO_ACTIVO)).toHaveTextContent(CONSULTORIO_NORTE.nombre);
    // El listado se pide con las bajas: sin ellas la celda vuelve a quedarse vacía.
    await waitFor(() => expect(getMock).toHaveBeenCalledWith(RUTA_LISTADO_CONSULTORIOS));
  });

  it('no ofrece «Horarios» para un médico inactivo y sí para uno activo (S10)', async () => {
    cargarCon([MEDICO_ACTIVO, MEDICO_INACTIVO], [CONSULTORIO_NORTE, CONSULTORIO_CENTRO]);

    renderConQueryClient(<MedicosPage />);

    await screen.findByText(MEDICO_INACTIVO.nombre);
    expect(
      within(filaDe(MEDICO_INACTIVO)).queryByRole('button', { name: /horarios/i }),
    ).not.toBeInTheDocument();
    expect(
      within(filaDe(MEDICO_ACTIVO)).getByRole('button', { name: /horarios/i }),
    ).toBeInTheDocument();
  });

  it('el alta no ofrece un consultorio dado de baja, aunque la tabla muestre su nombre', async () => {
    cargarCon([MEDICO_ACTIVO], [CONSULTORIO_NORTE, CONSULTORIO_CENTRO]);

    renderConQueryClient(<MedicosPage />);
    await screen.findByText(MEDICO_ACTIVO.nombre);

    fireEvent.click(screen.getByRole('button', { name: /nuevo médico/i }));

    const dialogo = screen.getByRole('dialog');
    const seleccion = within(dialogo).getByRole('combobox');
    expect(within(seleccion).queryByRole('option', { name: CONSULTORIO_NORTE.nombre })).toBeNull();
    expect(
      within(seleccion).getByRole('option', { name: CONSULTORIO_CENTRO.nombre }),
    ).toBeInTheDocument();
    // La tabla sigue mostrando el consultorio inactivo del médico existente (S12).
    expect(celdaDeConsultorio(MEDICO_ACTIVO)).toHaveTextContent(CONSULTORIO_NORTE.nombre);
  });
});
