import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authService } from '@/features/auth/auth.service';
import { useAuthStore } from '@/store/auth.store';
import { expVigente } from '../helpers/jwt';

/**
 * Adaptado en T6b (ADR-011): `login` ya no recibe un token — lo pide al emisor y lo guarda solo
 * en memoria. Los casos son los mismos (decodificación base64url/UTF-8 y limpieza al cerrar
 * sesión); lo único que cambió es la frontera: el servicio está mockeado para no tocar la red.
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
  exp: expVigente(),
};

/** Autentica con un token concreto, como haría el emisor al responder un login válido. */
async function iniciarSesionCon(accessToken: string) {
  servicio.login.mockResolvedValue({ accessToken });
  return useAuthStore.getState().login(CLAIMS.email, 'secreta');
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

describe('auth.store · decodificación del token', () => {
  it('decodifica los claims documentados de una carga útil base64url válida', async () => {
    await iniciarSesionCon(makeToken(CLAIMS));

    expect(useAuthStore.getState().user).toEqual({
      id: 'usr-0001',
      email: 'medico@clinica.mx',
      role: 'MEDICO',
      tenantId: 'org-001',
      medicoId: 'med-0001',
    });
    expect(useAuthStore.getState().accessToken).toBe(makeToken(CLAIMS));
  });

  it('decodifica claims en UTF-8 sin perder los caracteres acentuados', async () => {
    await iniciarSesionCon(makeUtf8Token({ ...CLAIMS, email: 'jósé@clínica.mx' }));

    expect(useAuthStore.getState().user?.email).toBe('jósé@clínica.mx');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('no lanza de forma inesperada y deja el usuario en null cuando el token está mal formado', async () => {
    await expect(iniciarSesionCon('no-es-un-jwt')).rejects.toThrow();
    expect(useAuthStore.getState().user).toBeNull();

    await expect(
      iniciarSesionCon(`${JWT_HEADER}.no-es-base64!.${JWT_SIGNATURE}`),
    ).rejects.toThrow();
    expect(useAuthStore.getState().user).toBeNull();
  });
});

describe('auth.store · logout', () => {
  it('limpia token, usuario, banderas y no deja artefactos de sesión en localStorage', async () => {
    await iniciarSesionCon(makeToken(CLAIMS));
    useAuthStore.getState().setShowInactivityWarning(true);

    await useAuthStore.getState().logout();

    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.user).toBeNull();
    expect(state.isAuthenticated).toBe(false);
    expect(state.showInactivityWarning).toBe(false);
    // La sesión en memoria no escribe ninguna clave: ni al entrar ni al salir.
    expect(Object.keys(localStorage)).toEqual([]);
  });
});
