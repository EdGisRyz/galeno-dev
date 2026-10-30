import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { ProtectedRoute } from '@/components/navigation/ProtectedRoute';
import { useAuthStore } from '@/store/auth.store';
import type { UserRole } from '@/features/auth/auth.types';
import { USUARIO } from '../../helpers/jwt';

/**
 * La única espera que gana ProtectedRoute en T6b (ADR-011): mientras el refresco silencioso de
 * arranque está en vuelo no se decide nada, para no mostrar el login a quien solo está
 * recargando la página.
 *
 * T7 agrega `allowedRoles` (ADR-013): un rol fuera de la lista no llega a los hijos y vuelve al
 * login con `reason=sin_acceso`.
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

/** Pantalla de login que publica su `location`: así se observa el `reason` del redirect. */
function PantallaDeLogin() {
  const location = useLocation();
  return <div>{`pantalla-de-login${location.search}`}</div>;
}

function renderizarRutaConRoles(allowedRoles: UserRole[]) {
  return render(
    <MemoryRouter initialEntries={['/agenda']}>
      <Routes>
        <Route path="/login" element={<PantallaDeLogin />} />
        <Route element={<ProtectedRoute allowedRoles={allowedRoles} />}>
          <Route path="/agenda" element={<div>agenda-clinica</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/** Sesión ya establecida con el rol indicado (el token no se decodifica aquí). */
function autenticarComo(role: UserRole) {
  useAuthStore.setState({
    accessToken: 'token-en-memoria',
    user: { ...USUARIO, role },
    isAuthenticated: true,
    inicializando: false,
  });
}

describe('ProtectedRoute · roles permitidos (ADR-013)', () => {
  const ROLES_DE_AGENDA: UserRole[] = ['ADMIN', 'MEDICO'];

  it('deja pasar a un ADMIN', () => {
    autenticarComo('ADMIN');

    renderizarRutaConRoles(ROLES_DE_AGENDA);

    expect(screen.getByText('agenda-clinica')).toBeInTheDocument();
  });

  it('deja pasar a un MEDICO', () => {
    autenticarComo('MEDICO');

    renderizarRutaConRoles(ROLES_DE_AGENDA);

    expect(screen.getByText('agenda-clinica')).toBeInTheDocument();
  });

  it('no renderiza los hijos para un PACIENTE y lo manda al login con reason=sin_acceso', () => {
    autenticarComo('PACIENTE');

    renderizarRutaConRoles(ROLES_DE_AGENDA);

    expect(screen.queryByText('agenda-clinica')).not.toBeInTheDocument();
    expect(screen.getByText('pantalla-de-login?reason=sin_acceso')).toBeInTheDocument();
  });

  it('sin sesión sigue yendo al login sin motivo de acceso', () => {
    useAuthStore.setState({ isAuthenticated: false, inicializando: false });

    renderizarRutaConRoles(ROLES_DE_AGENDA);

    expect(screen.getByText('pantalla-de-login')).toBeInTheDocument();
    expect(screen.queryByText('agenda-clinica')).not.toBeInTheDocument();
  });

  it('mientras inicializa no renderiza nada, ni siquiera con roles permitidos', () => {
    autenticarComo('ADMIN');
    useAuthStore.setState({ inicializando: true });

    const { container } = renderizarRutaConRoles(ROLES_DE_AGENDA);

    expect(container.textContent).toBe('');
    expect(screen.queryByText('agenda-clinica')).not.toBeInTheDocument();
    expect(screen.queryByText(/pantalla-de-login/)).not.toBeInTheDocument();
  });
});
