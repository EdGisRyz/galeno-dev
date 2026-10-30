import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/store/auth.store';

const JWT_HEADER = 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9';
const JWT_SIGNATURE = 'firma-simulada-rs256';

// El decodificador del store usa `atob`, así que la carga útil se codifica en base64url
// sin caracteres propios del alfabeto url-safe ('-', '_') ni relleno.
function toBase64Url(json: string): string {
  return btoa(json)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

function makeToken(payload: Record<string, unknown>): string {
  return `${JWT_HEADER}.${toBase64Url(JSON.stringify(payload))}.${JWT_SIGNATURE}`;
}

// Igual que `toBase64Url`, pero codificando el JSON como UTF-8 antes de base64:
// `btoa` rechaza cualquier carácter fuera de Latin-1, y las claims pueden llevar acentos.
function makeUtf8Token(payload: Record<string, unknown>): string {
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);

  return `${JWT_HEADER}.${btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')}.${JWT_SIGNATURE}`;
}

const CLAIMS = {
  sub: 'usr-0001',
  email: 'medico@clinica.mx',
  role: 'MEDICO',
  tenantId: 'org-001',
  medicoId: 'med-0001',
  exp: 1793000000,
};

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

describe('auth.store · decodificación del token', () => {
  it('decodifica los claims documentados de una carga útil base64url válida', () => {
    useAuthStore.getState().login(makeToken(CLAIMS));

    expect(useAuthStore.getState().user).toEqual({
      id: 'usr-0001',
      email: 'medico@clinica.mx',
      role: 'MEDICO',
      tenantId: 'org-001',
      medicoId: 'med-0001',
    });
    expect(useAuthStore.getState().accessToken).toBe(makeToken(CLAIMS));
  });

  it('decodifica claims en UTF-8 sin perder los caracteres acentuados', () => {
    useAuthStore.getState().login(makeUtf8Token({ ...CLAIMS, email: 'jósé@clínica.mx' }));

    expect(useAuthStore.getState().user?.email).toBe('jósé@clínica.mx');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('no lanza y deja el usuario en null cuando el token está mal formado', () => {
    expect(() => useAuthStore.getState().login('no-es-un-jwt')).not.toThrow();
    expect(useAuthStore.getState().user).toBeNull();

    expect(() =>
      useAuthStore.getState().login(`${JWT_HEADER}.no-es-base64!.${JWT_SIGNATURE}`),
    ).not.toThrow();
    expect(useAuthStore.getState().user).toBeNull();
  });
});

describe('auth.store · logout', () => {
  it('limpia token, usuario, banderas y los artefactos de sesión en localStorage', () => {
    useAuthStore.getState().login(makeToken(CLAIMS));
    useAuthStore.getState().setShowInactivityWarning(true);

    useAuthStore.getState().logout();

    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.isAuthenticated).toBe(false);
    expect(state.showInactivityWarning).toBe(false);
    expect(localStorage.getItem('access_token')).toBeNull();
    expect(localStorage.getItem('auth_user')).toBeNull();
  });
});
