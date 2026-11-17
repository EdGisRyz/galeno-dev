import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MainLayout } from '@/layouts/MainLayout';
import { useAuthStore } from '@/store/auth.store';
import { authService } from '@/features/auth/auth.service';
import { CLAIMS, makeToken } from '../helpers/jwt';

/**
 * Secciones anunciadas: agenda, expediente y cobros no tienen backend todavía.
 *
 * Se muestran en el menú para que el alcance quede a la vista, pero **no navegan**: abrir una
 * pantalla que solo tenía datos simulados deja un cartel de error que se lee como un defecto del
 * sistema. La prueba fija las dos mitades: el nombre sigue visible y no hay enlace que lo abra.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

const ANUNCIADAS = ['Agenda y Citas', 'Expediente Clínico', 'Cobros & Caja'];

function renderizarLayout() {
  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <Routes>
        <Route path="/login" element={<div>pantalla-de-login</div>} />
        <Route element={<MainLayout />}>
          <Route path="/agenda" element={<div>agenda-clinica</div>} />
          <Route path="/pacientes" element={<div>directorio-de-pacientes</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  servicio.login.mockReset();
  servicio.logout.mockReset();
  servicio.logout.mockResolvedValue(undefined);

  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: false,
    tenantSlug: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('MainLayout · secciones anunciadas', () => {
  beforeEach(async () => {
    servicio.login.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, tenantId: 'org-7f3c9a21' }),
    });
    await useAuthStore.getState().login(CLAIMS.email, 'secreta');
    renderizarLayout();
  });

  it('muestra las tres secciones anunciadas con su etiqueta', () => {
    for (const nombre of ANUNCIADAS) {
      expect(screen.getByText(nombre)).toBeInTheDocument();
      expect(screen.getAllByText('Próximamente')).toHaveLength(ANUNCIADAS.length);
    }
  });

  it('no ofrece un enlace que abra una pantalla sin backend', () => {
    for (const nombre of ANUNCIADAS) {
      expect(screen.queryByRole('link', { name: nombre })).toBeNull();
      const fila = screen.getByText(nombre).closest('[aria-disabled="true"]');
      expect(fila).not.toBeNull();
    }
  });

  it('las secciones que sí funcionan siguen siendo enlaces', () => {
    expect(screen.getByRole('link', { name: /Pacientes/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Consultorios/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Médicos/ })).toBeInTheDocument();
  });
});
