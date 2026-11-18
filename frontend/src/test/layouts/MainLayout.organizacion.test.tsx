import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MainLayout } from '@/layouts/MainLayout';
import { useAuthStore } from '@/store/auth.store';
import { authService } from '@/features/auth/auth.service';
import { organizacionService } from '@/features/organizaciones/organizacion.service';
import { CLAIMS, USUARIO, makeToken } from '../helpers/jwt';

/**
 * S1 y S5 · la cabecera pinta el nombre de la organización y cae al identificador del tenant.
 *
 * - Con nombre disponible, se muestra el nombre de la clínica (no el UUID).
 * - Sin nombre —consulta fallida o en curso—, nunca queda vacía: se pinta el tenant.
 * - La consulta se hace al iniciar la sesión, no en cada render ni en cada navegación.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('@/features/organizaciones/organizacion.service', () => ({
  organizacionService: {
    obtenerPorId: vi.fn(),
  },
}));

const servicioAuth = vi.mocked(authService);
const servicioOrganizacion = vi.mocked(organizacionService);

const TENANT_DEL_CLAIM = 'org-7f3c9a21';
const NOMBRE_DE_LA_CLINICA = 'Clínica del Valle';

function renderizarLayout() {
  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <Routes>
        <Route path="/login" element={<div>pantalla-de-login</div>} />
        <Route element={<MainLayout />}>
          <Route path="/agenda" element={<div>agenda-clinica</div>} />
          <Route path="/pacientes" element={<div>pacientes-clinica</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  servicioAuth.login.mockReset();
  servicioAuth.logout.mockReset();
  servicioAuth.logout.mockResolvedValue(undefined);
  servicioOrganizacion.obtenerPorId.mockReset();

  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: false,
    tenantSlug: '',
    organizacionNombre: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('MainLayout · nombre de la organización en la cabecera', () => {
  it('muestra el nombre de la clínica cuando la consulta responde', async () => {
    servicioAuth.login.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, tenantId: TENANT_DEL_CLAIM }),
    });
    servicioOrganizacion.obtenerPorId.mockResolvedValue({
      id: TENANT_DEL_CLAIM,
      nombre: NOMBRE_DE_LA_CLINICA,
      plan: 'CLINICA',
      activo: true,
      createdAt: '2026-01-01T00:00:00.000Z',
    });

    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(NOMBRE_DE_LA_CLINICA),
    );

    renderizarLayout();

    expect(screen.getByText(NOMBRE_DE_LA_CLINICA)).toBeInTheDocument();
    expect(screen.queryByText(TENANT_DEL_CLAIM)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/vitalix|san rafael/i);
  });

  it('si la consulta falla, pinta el identificador del tenant y no deja la cabecera vacía', async () => {
    servicioAuth.login.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, tenantId: TENANT_DEL_CLAIM }),
    });
    servicioOrganizacion.obtenerPorId.mockRejectedValue(new Error('403'));

    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    renderizarLayout();

    expect(screen.getByText(TENANT_DEL_CLAIM)).toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/vitalix|san rafael/i);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('un PACIENTE sin celda de organización ve su tenant, nunca una cabecera vacía', async () => {
    useAuthStore.setState({
      accessToken: 'token-en-memoria',
      user: { ...USUARIO, role: 'PACIENTE', tenantId: TENANT_DEL_CLAIM },
      isAuthenticated: true,
      inicializando: false,
      tenantSlug: TENANT_DEL_CLAIM,
      organizacionNombre: '',
    });

    renderizarLayout();

    expect(screen.getByText(TENANT_DEL_CLAIM)).toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
  });

  it('la consulta no se repite al re-renderizar ni al navegar dentro de la sesión', async () => {
    servicioAuth.login.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, tenantId: TENANT_DEL_CLAIM }),
    });
    servicioOrganizacion.obtenerPorId.mockResolvedValue({
      id: TENANT_DEL_CLAIM,
      nombre: NOMBRE_DE_LA_CLINICA,
      plan: 'CLINICA',
      activo: true,
      createdAt: '2026-01-01T00:00:00.000Z',
    });

    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    await vi.waitFor(() =>
      expect(useAuthStore.getState().organizacionNombre).toBe(NOMBRE_DE_LA_CLINICA),
    );
    const { rerender } = renderizarLayout();
    expect(screen.getByText(NOMBRE_DE_LA_CLINICA)).toBeInTheDocument();

    rerender(
      <MemoryRouter initialEntries={['/agenda']}>
        <Routes>
          <Route path="/login" element={<div>pantalla-de-login</div>} />
          <Route element={<MainLayout />}>
            <Route path="/agenda" element={<div>agenda-clinica</div>} />
            <Route path="/pacientes" element={<div>pacientes-clinica</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('link', { name: /pacientes/i }));

    expect(screen.getByText('pacientes-clinica')).toBeInTheDocument();
    expect(servicioOrganizacion.obtenerPorId).toHaveBeenCalledTimes(1);
  });
});
