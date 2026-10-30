import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MainLayout } from '@/layouts/MainLayout';
import { useAuthStore } from '@/store/auth.store';
import { authService } from '@/features/auth/auth.service';
import { CLAIMS, USUARIO, makeToken } from '../helpers/jwt';

/**
 * T7 · consistencia del cierre de sesión.
 *
 * `handleLogout` llamaba a `logout()` sin esperar y navegaba enseguida: el navegador abortaba el
 * `POST /auth/logout` y la cookie `httpOnly` sobrevivía, con lo que el arranque volvía a
 * autenticar la sesión (R-SEG-08). El cierre por inactividad ya esperaba; el botón "Salir" ahora
 * hace lo mismo: primero el emisor, después la navegación.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

/** Promesa que el test resuelve a mano para observar qué pasa *mientras* el emisor responde. */
function diferir() {
  let resolver: () => void = () => {};
  const promesa = new Promise<void>((resolve) => {
    resolver = resolve;
  });
  return { promesa, resolver };
}

function renderizarLayout() {
  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <Routes>
        <Route path="/login" element={<div>pantalla-de-login</div>} />
        <Route element={<MainLayout />}>
          <Route path="/agenda" element={<div>agenda-clinica</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  servicio.login.mockReset();
  servicio.logout.mockReset();

  useAuthStore.setState({
    accessToken: makeToken(CLAIMS),
    user: USUARIO,
    isAuthenticated: true,
    inicializando: false,
    tenantSlug: USUARIO.tenantId,
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('MainLayout · el botón Salir espera el cierre en el emisor', () => {
  it('no navega al login hasta que el emisor confirma el cierre de sesión', async () => {
    const cierre = diferir();
    servicio.logout.mockReturnValue(cierre.promesa);

    renderizarLayout();
    fireEvent.click(screen.getByTitle('Cerrar sesión segura'));

    // El emisor todavía no respondió: el cierre local ya ocurrió (memoria limpia), pero la
    // navegación no puede adelantarse o el navegador abortaría el POST y la cookie sobreviviría.
    expect(servicio.logout).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(screen.queryByText('pantalla-de-login')).not.toBeInTheDocument();

    await act(async () => {
      cierre.resolver();
      await cierre.promesa;
    });

    expect(await screen.findByText('pantalla-de-login')).toBeInTheDocument();
  });
});
