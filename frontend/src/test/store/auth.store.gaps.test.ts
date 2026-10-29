import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/auth.store';

/**
 * Contratos del store que R-SEG-07 exige y que T6a cierra.
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
 */

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
  exp: 1793000000,
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

beforeEach(() => {
  localStorage.clear();
  useAuthStore.setState({
    accessToken: null,
    user: null,
    isAuthenticated: false,
    lastActivity: Date.now(),
    showInactivityWarning: false,
  });
});

describe('auth.store · alfabeto base64url (T6a)', () => {
  it('decodifica un payload base64url con "-" que `atob` rechaza por sí solo', () => {
    const encoded = toBase64Url(JSON.stringify(URL_SAFE_CLAIMS));

    // El fixture realmente usa el alfabeto url-safe y `atob` realmente lo rechaza:
    // la brecha estaba en el decodificador, no en el test.
    expect(encoded).toContain('-');
    expect(() => atob(encoded)).toThrow();

    useAuthStore.getState().login(makeToken(encoded));

    expect(useAuthStore.getState().user).toMatchObject({
      id: 'usr-0002',
      email: 'jramirez~@clinica.mx',
      role: 'ADMIN',
      tenantId: 'org-002',
    });
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(useAuthStore.getState().accessToken).toBe(makeToken(encoded));
  });

  it('un payload base64url url-safe debe decodificar id, role y tenantId', () => {
    useAuthStore.getState().login(URL_SAFE_TOKEN);

    expect(useAuthStore.getState().user).toMatchObject({
      id: 'usr-0002',
      role: 'ADMIN',
      tenantId: 'org-002',
    });
  });
});

describe('auth.store · fail-open del login (T6a)', () => {
  it('un token que no se puede decodificar no debe dejar la sesión autenticada', () => {
    useAuthStore.getState().login('no-es-un-jwt');

    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('auth_user')).toBeNull();
  });
});
