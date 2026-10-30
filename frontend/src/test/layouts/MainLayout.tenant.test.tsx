import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MainLayout } from '@/layouts/MainLayout';
import { useAuthStore } from '@/store/auth.store';
import { authService } from '@/features/auth/auth.service';
import { CLAIMS, USUARIO, makeToken } from '../helpers/jwt';

/**
 * T7 · el nombre de la clínica sale del claim `tenantId` de la sesión (guardarraíl 4).
 *
 * Antes la cabecera del sidebar mostraba una clínica inventada cuando el claim faltaba
 * (`tenantSlug || 'vitalix'`). Ahora se pinta el tenant de la sesión y, si no hay ninguno, un
 * guion neutro: nunca el nombre de una clínica que el emisor no emitió.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

const TENANT_DEL_CLAIM = 'org-7f3c9a21';

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

describe('MainLayout · tenant de la sesión', () => {
  it('muestra el tenant del claim, no un nombre de clínica inventado', async () => {
    servicio.login.mockResolvedValue({
      accessToken: makeToken({ ...CLAIMS, tenantId: TENANT_DEL_CLAIM }),
    });
    await useAuthStore.getState().login(CLAIMS.email, 'secreta');

    renderizarLayout();

    expect(screen.getByText(TENANT_DEL_CLAIM)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/vitalix|san rafael/i);
  });

  it('sin tenant en la sesión pinta un guion neutro, nunca una clínica inventada', () => {
    // Sesión sin tenant: el store ya no puede producirla (rechaza el token), así que el estado se
    // construye a mano para comprobar que el layout tampoco inventa un nombre al pintar.
    useAuthStore.setState({
      accessToken: 'token-en-memoria',
      user: { ...USUARIO, tenantId: '' },
      isAuthenticated: true,
      inicializando: false,
      tenantSlug: '',
    });

    renderizarLayout();

    expect(screen.getByText('—')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/vitalix|san rafael/i);
  });
});
