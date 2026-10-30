import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';

/**
 * Contratos del store que R-SEG-07 exige y que T6a cerró.
 *
 * 1. Decodificación base64url: `atob` rechaza el alfabeto url-safe ('-', '_'), así que
 *    `decodeJwtPayload` debe traducirlo antes de decodificar; y las claims en UTF-8
 *    (acentos) deben sobrevivir.
 * 2. Login fail-closed: un token que no se puede decodificar no puede dejar la sesión
 *    autenticada.
 *
 * Los casos 1 y 2 se escribieron como `it.fails` cuando el comportamiento todavía no
 * existía. T6a los implementó, así que ahora son `it` normales: si vuelven a fallar, es
 * que el contrato se rompió. No se borran: son el criterio de aceptación.
 *
 * Adaptado en T6b (ADR-011): `login` ya no recibe el token desde la vista — lo obtiene del
 * emisor y lo guarda solo en memoria. Los dos contratos se comprueban igual, con el servicio
 * mockeado en su frontera.
 */

vi.mock('@/features/auth/auth.service', () => ({
  authService: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
  },
}));

const servicio = vi.mocked(authService);

const JWT_HEADER = 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9';
const JWT_SIGNATURE = 'firma-simulada-rs256';

/**
 * Claims de ejemplo cuyo segmento codificado contiene '-' porque el '~' de la parte local del
 * correo (válido en RFC 5322) desplaza los grupos base64 hasta el carácter 62 del alfabeto.
 */
const URL_SAFE_CLAIMS = {
  sub: 'usr-0002',
  email: 'jramirez~@clinica.mx',
  role: 'ADMIN',
  tenantId: 'org-002',
  exp: Math.floor(Date.now() / 1000) + 1800,
};

function toBase64Url(json: string): string {
  return btoa(json)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function makeToken(encodedPayload: string): string {
  return `${JWT_HEADER}.${encodedPayload}.${JWT_SIGNATURE}`;
}

const URL_SAFE_TOKEN = makeToken(toBase64Url(JSON.stringify(URL_SAFE_CLAIMS)));

async function iniciarSesionCon(accessToken: string) {
  servicio.login.mockResolvedValue({ accessToken });
  return useAuthStore.getState().login(URL_SAFE_CLAIMS.email, 'secreta');
}

beforeEach(() => {
  localStorage.clear();
  servicio.login.mockReset();
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

describe('auth.store · alfabeto base64url (T6a)', () => {
  it('decodifica un payload base64url con "-" que `atob` rechaza por sí solo', async () => {
    const encoded = toBase64Url(JSON.stringify(URL_SAFE_CLAIMS));

    // El fixture realmente usa el alfabeto url-safe y `atob` realmente lo rechaza:
    // la brecha estaba en el decodificador, no en el test.
    expect(encoded).toContain('-');
    expect(() => atob(encoded)).toThrow();

    await iniciarSesionCon(makeToken(encoded));

    expect(useAuthStore.getState().user).toMatchObject({
      id: 'usr-0002',
      email: 'jramirez~@clinica.mx',
      role: 'ADMIN',
      tenantId: 'org-002',
    });
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(useAuthStore.getState().accessToken).toBe(makeToken(encoded));
  });

  it('un payload base64url url-safe debe decodificar id, role y tenantId', async () => {
    await iniciarSesionCon(URL_SAFE_TOKEN);

    expect(useAuthStore.getState().user).toMatchObject({
      id: 'usr-0002',
      role: 'ADMIN',
      tenantId: 'org-002',
    });
  });
});

describe('auth.store · fail-open del login (T6a)', () => {
  it('un token que no se puede decodificar no debe dejar la sesión autenticada', async () => {
    await expect(iniciarSesionCon('no-es-un-jwt')).rejects.toThrow();

    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(Object.keys(localStorage)).toEqual([]);
  });
});
