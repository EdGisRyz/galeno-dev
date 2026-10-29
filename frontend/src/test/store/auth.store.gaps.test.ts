import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/auth.store';

/**
 * Caracterización de los dos contratos del store que T6/T7 (R-SEG-07) tienen que cambiar.
 *
 * 1. Un payload cuya forma base64url usa el alfabeto url-safe ('-', '_') no se decodifica:
 *    `decodeJwtPayload` llama a `atob`, que lanza, y el usuario queda en null.
 * 2. `login()` deja la sesión autenticada aunque no haya podido decodificar el token (fail-open).
 *
 * Los casos que describen el comportamiento *deseado* se escriben con `it.fails`: hoy no se
 * cumplen, la suite queda verde y, el día que T6/T7 los corrijan, el marcador empieza a fallar
 * para que se convierta en un `it` normal. No se borran: son el registro del objetivo pendiente.
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

describe('auth.store · brecha del alfabeto base64url (objetivo T6/T7)', () => {
  it('T6/T7 · hoy un payload base64url con "-" no se decodifica y deja el usuario en null', () => {
    const encoded = toBase64Url(JSON.stringify(URL_SAFE_CLAIMS));

    // El fixture realmente usa el alfabeto url-safe y `atob` realmente lo rechaza:
    // la brecha está en el decodificador, no en el test.
    expect(encoded).toContain('-');
    expect(() => atob(encoded)).toThrow();

    useAuthStore.getState().login(makeToken(encoded));

    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBe(makeToken(encoded));
  });

  it.fails(
    'T6/T7 · un payload base64url url-safe debe decodificar id, role y tenantId',
    () => {
      useAuthStore.getState().login(URL_SAFE_TOKEN);

      expect(useAuthStore.getState().user).toMatchObject({
        id: 'usr-0002',
        role: 'ADMIN',
        tenantId: 'org-002',
      });
    },
  );
});

describe('auth.store · fail-open del login (objetivo T6/T7)', () => {
  it.fails(
    'T6/T7 · un token que no se puede decodificar no debe dejar la sesión autenticada',
    () => {
      useAuthStore.getState().login('no-es-un-jwt');

      expect(useAuthStore.getState().user).toBeNull();
      // Hoy es true: `login()` marca la sesión como autenticada sin usuario resuelto.
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
    },
  );
});
