// Store de autenticación y sesión clínica con Zustand.
// Gestiona el token JWT RS256, los datos del usuario, el rol y el timeout de inactividad (HIPAA).

import { create } from 'zustand';
import type { UserRole } from '@/types/models';

export interface AuthUser {
  id: string;
  email: string;
  role: UserRole;
  tenantId: string;
  medicoId?: string | null;
  nombre?: string;
  cedula?: string;
  especialidad?: string;
}

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

// Decodificador seguro de payload JWT en navegador
function decodeJwtPayload(token: string): Partial<AuthUser> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = JSON.parse(atob(parts[1]));
    return {
      id: payload.sub,
      email: payload.email,
      role: payload.role as UserRole,
      tenantId: payload.tenantId,
      medicoId: payload.medicoId,
    };
  } catch {
    return null;
  }
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
  const decoded = decodeJwtPayload(savedToken);
  if (decoded && decoded.id && decoded.email && decoded.role) {
    initialUser = {
      id: decoded.id,
      email: decoded.email,
      role: decoded.role,
      tenantId: decoded.tenantId || 'org-001',
      medicoId: decoded.medicoId,
    };
  }
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: savedToken,
  user: initialUser,
  isAuthenticated: !!savedToken,
  tenantSlug: localStorage.getItem('tenant_slug') || 'sanrafael',
  lastActivity: Date.now(),
  showInactivityWarning: false,

  login: (accessToken, user) => {
    localStorage.setItem('access_token', accessToken);
    let resolvedUser = user;

    if (!resolvedUser) {
      const decoded = decodeJwtPayload(accessToken);
      if (decoded && decoded.id && decoded.email && decoded.role) {
        resolvedUser = {
          id: decoded.id,
          email: decoded.email,
          role: decoded.role,
          tenantId: decoded.tenantId || 'org-001',
          medicoId: decoded.medicoId,
        };
      }
    }

    if (resolvedUser) {
      localStorage.setItem('auth_user', JSON.stringify(resolvedUser));
    }

    set({
      accessToken,
      user: resolvedUser || null,
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