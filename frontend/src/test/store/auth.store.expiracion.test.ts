import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import { CLAIMS, USUARIO, expVencido, expVigente, makeToken } from '../helpers/jwt';

/**
 * T7 · ADR-010 (`exp` obligatorio) y ADR-012 (token de 30 minutos).
 *
 * Hasta hoy nada miraba `exp`: un token emitido una hora antes seguía produciendo una sesión
 * autenticada, así que el cierre por inactividad de 30 minutos era cosmético. La comprobación
 * vive en el único camino que abre sesión (`establecerSesion`), de modo que login y refresco de
 * arranque caen por la misma puerta; un token sin `exp`, con `exp` no numérico o con `exp` en el
 * pasado no autentica y además borra lo que hubiera en memoria.
 *
 * El servicio se mockea en su frontera: ningún test toca la red.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

/** Estado observable de una sesión cerrada: token, usuario y bandera, no detalles internos. */
const SESION_CERRADA = { accessToken: null, user: null, isAuthenticated: false };

function estadoDeSesion() {
  const estado = useAuthStore.getState();
  return {
    accessToken: estado.accessToken,
    user: estado.user,
    isAuthenticated: estado.isAuthenticated,
  };
}

/** Claims sin un claim concreto, sin recurrir a destructuring que eslint marca como no usado. */
function sinClaim(claim: string): Record<string, unknown> {
  const copia: Record<string, unknown> = { ...CLAIMS };
  delete copia[claim];
  return copia;
}

/** Intenta abrir sesión con el payload indicado, como haría el emisor al responder un login. */
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

describe('auth.store · vigencia del token en el login (ADR-010, ADR-012)', () => {
  it('un token vencido hace una hora no deja sesión autenticada', async () => {
    await expect(intentarEntrarCon({ ...CLAIMS, exp: expVencido(3600) })).rejects.toThrow();

    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
    expect(Object.keys(localStorage)).toEqual([]);
  });

  it('un token sin `exp` no deja sesión autenticada: el claim es obligatorio', async () => {
    await expect(intentarEntrarCon(sinClaim('exp'))).rejects.toThrow();

    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
  });

  it('un `exp` que no es numérico se rechaza igual que uno ausente', async () => {
    await expect(intentarEntrarCon({ ...CLAIMS, exp: '1793000000' })).rejects.toThrow();

    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
  });

  it('un token que vence en unos segundos todavía autentica', async () => {
    const usuario = await intentarEntrarCon({ ...CLAIMS, exp: expVigente(60) });

    expect(usuario).toEqual(USUARIO);
    const estado = useAuthStore.getState();
    expect(estado.isAuthenticated).toBe(true);
    expect(estado.user).toEqual(USUARIO);
    expect(estado.accessToken).toBe(makeToken({ ...CLAIMS, exp: expVigente(60) }));
  });

  it('un token que venció hace unos segundos ya no autentica', async () => {
    await expect(intentarEntrarCon({ ...CLAIMS, exp: expVencido(5) })).rejects.toThrow();

    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
  });
});

describe('auth.store · vigencia del token en el refresco de arranque', () => {
  it('un refresco con token vencido deja la sesión anónima y limpia la memoria', async () => {
    // Sesión previa en memoria: el refresco llega con un token que ya caducó.
    useAuthStore.setState({
      accessToken: 'token-anterior',
      user: USUARIO,
      isAuthenticated: true,
      inicializando: true,
      tenantSlug: USUARIO.tenantId,
    });
    servicio.refresh.mockResolvedValue({ accessToken: makeToken({ ...CLAIMS, exp: expVencido(3600) }) });

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBeNull();
    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
    expect(useAuthStore.getState().tenantSlug).toBe('');
    expect(useAuthStore.getState().inicializando).toBe(false);
  });

  it('un refresco con token sin `exp` deja la sesión anónima', async () => {
    servicio.refresh.mockResolvedValue({ accessToken: makeToken(sinClaim('exp')) });

    const resultado = await useAuthStore.getState().refresh();

    expect(resultado).toBeNull();
    expect(estadoDeSesion()).toEqual(SESION_CERRADA);
    expect(useAuthStore.getState().inicializando).toBe(false);
  });
});
