import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosResponse } from 'axios';
import { api } from '@/lib/axios';
import { PacientesPage } from '@/features/pacientes/PacientesPage';
import { renderConQueryClient } from '@/test/testUtils';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import { useAuthStore } from '@/store/auth.store';
import { USUARIO } from '../../helpers/jwt';

/**
 * Qué acciones ofrece la pantalla según el rol.
 *
 * Las escrituras de paciente son de administración y recepción en la matriz; el médico solo
 * consulta. Antes, la pantalla no miraba el rol: con una sesión de médico mostraba «Nuevo
 * Paciente», «Ficha» y «Desactivar», y el backend respondía 403 a las tres. Una pantalla que
 * ofrece lo que el servidor va a negar es peor que una que no lo ofrece.
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

const ORGANIZACION_ID = 'org-001';

const PACIENTE: Paciente = {
  id: 'pac-0001',
  organizacionId: ORGANIZACION_ID,
  nombre: 'María Fernanda López',
  telefono: '9512223344',
  fechaNacimiento: '1990-05-04',
  sexo: 'F',
  email: null,
  notas: null,
  activo: true,
  createdAt: '2026-01-01T00:00:00Z',
  curp: null,
  ocupacion: null,
  domicilio: null,
  nombreResponsable: null,
  telefonoResponsable: null,
  parentesco: null,
};

function respuestaCon<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>;
}

function montarConRol(role: 'ADMIN' | 'MEDICO' | 'RECEPCIONISTA' | 'PACIENTE') {
  useAuthStore.setState({
    accessToken: 'token-en-memoria',
    user: { ...USUARIO, role, tenantId: ORGANIZACION_ID },
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: ORGANIZACION_ID,
    organizacionNombre: 'Consultorio Demo',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });

  renderConQueryClient(<PacientesPage />);
}

beforeEach(() => {
  getMock.mockReset();
  getMock.mockResolvedValue(respuestaCon([PACIENTE]));
});

describe('PacientesPage · acciones según el rol', () => {
  it('un médico no ve ninguna acción de escritura', async () => {
    montarConRol('MEDICO');
    await screen.findByText('María Fernanda López');

    expect(screen.queryByRole('button', { name: /nuevo paciente/i })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: /acciones/i })).toBeNull();
    expect(screen.queryByTitle('Dar de baja al paciente')).toBeNull();
    expect(screen.queryByTitle('Editar ficha del paciente')).toBeNull();
  });

  it('administración ve el alta y las acciones de cada fila', async () => {
    montarConRol('ADMIN');
    await screen.findByText('María Fernanda López');

    expect(screen.getByRole('button', { name: /nuevo paciente/i })).toBeInTheDocument();
    expect(screen.getByTitle('Dar de baja al paciente')).toBeInTheDocument();
    expect(screen.getByTitle('Editar ficha del paciente')).toBeInTheDocument();
  });

  it('recepción también gestiona pacientes: es su función principal', async () => {
    montarConRol('RECEPCIONISTA');
    await screen.findByText('María Fernanda López');

    expect(screen.getByRole('button', { name: /nuevo paciente/i })).toBeInTheDocument();
    expect(screen.getByTitle('Editar ficha del paciente')).toBeInTheDocument();
  });
});
