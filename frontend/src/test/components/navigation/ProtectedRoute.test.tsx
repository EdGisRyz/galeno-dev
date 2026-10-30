import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { ProtectedRoute } from '@/components/navigation/ProtectedRoute';
import { useAuthStore } from '@/store/auth.store';
import { USUARIO } from '../../helpers/jwt';

/**
 * La única espera que gana ProtectedRoute en T6b (ADR-011): mientras el refresco silencioso de
 * arranque está en vuelo no se decide nada, para no mostrar el login a quien solo está
 * recargando la página. Sigue sin haber comprobación de roles: eso es T7.
 */

function renderizarRutaProtegida() {
  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <Routes>
        <Route path="/login" element={<div>pantalla-de-login</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/agenda" element={<div>agenda-clinica</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    inicializando: true,
    tenantSlug: '',
    showInactivityWarning: false,
    lastActivity: Date.now(),
  });
});

describe('ProtectedRoute · espera al refresco de arranque', () => {
  it('mientras inicializa no muestra el login ni la ruta protegida', () => {
    const { container } = renderizarRutaProtegida();

    expect(container.textContent).toBe('');
    expect(screen.queryByText('pantalla-de-login')).not.toBeInTheDocument();
  });

  it('cuando el refresco termina sin sesión redirige al login', () => {
    useAuthStore.setState({ inicializando: false });

    renderizarRutaProtegida();

    expect(screen.getByText('pantalla-de-login')).toBeInTheDocument();
  });

  it('cuando el refresco termina con sesión renderiza la ruta protegida', () => {
    useAuthStore.setState({
      accessToken: 'token-en-memoria',
      user: USUARIO,
      isAuthenticated: true,
      inicializando: false,
    });

    renderizarRutaProtegida();

    expect(screen.getByText('agenda-clinica')).toBeInTheDocument();
  });
});
