import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, makeToken } from '../helpers/jwt';

/**
 * T7 · el tenant de la sesión sale del claim `tenantId` o no hay sesión (ADR-013).
 *
 * Antes el store hacía `decoded.tenantId || 'org-001'`: un token sin tenant quedaba autenticado
 * dentro de una organización inventada, y la UI mostraba una clínica que nadie le dio. Ahora el
 * claim es obligatorio y el valor por defecto no existe (plan §2 guardarraíl 4).
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

function sinClaim(claim: string): Record<string, unknown> {
  const copia: Record<string, unknown> = { ...CLAIMS };
  delete copia[claim];
  return copia;
}

async function intentarEntrarCon(payload: Record<string, unknown>) {
  servicio.login.mockResolvedValue({ accessToken: makeToken(payload) });
  return useAuthStore.getState().login(CLAIMS.email, 'secreta');
}

beforeEach(() => {
  localStorage.clear();
  servicio.login.mockReset();
  servicio.refresh.mockReset();
  servicio.logout.mockReset();

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

describe('auth.store · tenant del claim (guardarraíl 4)', () => {
  it('un token sin `tenantId` no autentica: no hay tenant por defecto', async () => {
    await expect(intentarEntrarCon(sinClaim('tenantId'))).rejects.toThrow();

    const estado = useAuthStore.getState();
    expect(estado.accessToken).toBeNull();
    expect(estado.user).toBeNull();
    expect(estado.isAuthenticated).toBe(false);
    expect(estado.tenantSlug).toBe('');
  });

  it('el tenant de la sesión es el del claim, no un literal inventado', async () => {
    await intentarEntrarCon({ ...CLAIMS, tenantId: 'org-7f3c9a21' });

    const estado = useAuthStore.getState();
    expect(estado.user?.tenantId).toBe('org-7f3c9a21');
    expect(estado.tenantSlug).toBe('org-7f3c9a21');
    expect(estado.isAuthenticated).toBe(true);
  });

  it('el refresco de arranque tampoco inventa un tenant', async () => {
    servicio.refresh.mockResolvedValue({ accessToken: makeToken(sinClaim('tenantId')) });

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().tenantSlug).toBe('');
  });
});
