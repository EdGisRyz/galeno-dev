// Store de autenticación y sesión clínica con Zustand.
// Gestiona el token JWT RS256, los datos del usuario, el rol y el timeout de inactividad (HIPAA).

import { create } from 'zustand';
import type { AuthUser, UserRole } from '@/features/auth/auth.types';

interface AuthState {
  accessToken: string | null;
  user: AuthUser | null;
  isAuthenticated: boolean;
  tenantSlug: string;
  lastActivity: number;
  showInactivityWarning: boolean;

  login: (accessToken: string, user?: AuthUser) => void;
  logout: () => void;
  updateActivity: () => void;
  setShowInactivityWarning: (show: boolean) => void;
}

/**
 * Decodifica un segmento en base64url (RFC 7515 §2) a bytes.
 * `atob` solo entiende el alfabeto base64 estándar y exige relleno: hay que traducir
 * '-'(62) → '+' y '_'(63) → '/' y reponer '=' hasta un múltiplo de 4.
 */
function decodeBase64UrlSegment(segment: string): Uint8Array {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const remainder = base64.length % 4;
  const padded = remainder === 0 ? base64 : base64 + '='.repeat(4 - remainder);
  const binary = atob(padded);

  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/**
 * Decodificador seguro de payload JWT en navegador: base64url + UTF-8.
 * Devuelve `null` ante cualquier entrada mal formada; nunca lanza.
 */
function decodeJwtPayload(token: string): Partial<AuthUser> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;

    // `fatal: true` para tratar como mal formado un payload que no sea UTF-8 válido,
    // en lugar de decodificarlo con caracteres de reemplazo.
    const json = new TextDecoder('utf-8', { fatal: true }).decode(decodeBase64UrlSegment(parts[1]));
    const payload = JSON.parse(json) as Record<string, unknown>;

    return {
      id: payload.sub as string,
      email: payload.email as string,
      role: payload.role as UserRole,
      tenantId: payload.tenantId as string,
      medicoId: payload.medicoId as string,
    };
  } catch {
    return null;
  }
}

/**
 * Proyecta los claims decodificados a la sesión de la UI.
 * Sin identidad completa (`sub`, `email`, `role`) no hay usuario: fail-closed.
 */
function usuarioDesdeClaims(decoded: Partial<AuthUser> | null): AuthUser | null {
  if (!decoded || !decoded.id || !decoded.email || !decoded.role) return null;

  return {
    id: decoded.id,
    email: decoded.email,
    role: decoded.role,
    tenantId: decoded.tenantId || 'org-001',
    medicoId: decoded.medicoId,
  };
}

const savedToken = localStorage.getItem('access_token');
const savedUser = localStorage.getItem('auth_user');
let initialUser: AuthUser | null = null;

if (savedUser) {
  try {
    initialUser = JSON.parse(savedUser);
  } catch {
    initialUser = null;
  }
} else if (savedToken) {
  initialUser = usuarioDesdeClaims(decodeJwtPayload(savedToken));
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: savedToken,
  user: initialUser,
  isAuthenticated: !!savedToken,
  tenantSlug: localStorage.getItem('tenant_slug') || 'sanrafael',
  lastActivity: Date.now(),
  showInactivityWarning: false,

  login: (accessToken, user) => {
    // Fail-closed (R-SEG-07): sin un payload verificable no se abre sesión ni se persiste nada.
    // Antes bastaba con guardar el token para quedar autenticado con `user: null`.
    const decoded = decodeJwtPayload(accessToken);
    if (!decoded) return;

    const resolvedUser = user ?? usuarioDesdeClaims(decoded);
    if (!resolvedUser) return;

    localStorage.setItem('access_token', accessToken);
    localStorage.setItem('auth_user', JSON.stringify(resolvedUser));

    set({
      accessToken,
      user: resolvedUser,
      isAuthenticated: true,
      lastActivity: Date.now(),
      showInactivityWarning: false,
    });
  },

  logout: () => {
    localStorage.removeItem('access_token');
    localStorage.removeItem('auth_user');
    set({
      accessToken: null,
      user: null,
      isAuthenticated: false,
      showInactivityWarning: false,
    });
  },

  updateActivity: () => {
    set({ lastActivity: Date.now(), showInactivityWarning: false });
  },

  setShowInactivityWarning: (show) => {
    set({ showInactivityWarning: show });
  },
}));