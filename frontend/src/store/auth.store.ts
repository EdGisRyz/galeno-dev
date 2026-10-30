// Store de autenticación y sesión clínica con Zustand.
//
// La sesión vive **solo en memoria** (ADR-011): el token de acceso y el usuario nunca se escriben
// en `localStorage`, así que un XSS no puede leer una sesión del disco. El refresco viaja en una
// cookie `httpOnly` que este código no ve; al recargar la página la sesión se recupera con un
// refresco silencioso.

import { create } from 'zustand';
import { conectarSesionHttp } from '@/lib/axios';
import { authService } from '@/features/auth/auth.service';
import type { AuthUser, UserRole } from '@/features/auth/auth.types';

interface AuthState {
  accessToken: string | null;
  user: AuthUser | null;
  isAuthenticated: boolean;
  /** Hay un refresco de arranque en vuelo: la sesión todavía no es anónima ni autenticada. */
  inicializando: boolean;
  /**
   * Slug de la clínica. Hoy no hay contrato que lo emita y **no se inventa** un valor por defecto
   * (GAL-Front001 §2, guardarraíl 4): queda vacío. T7 lo deriva del claim `tenantId`.
   */
  tenantSlug: string;
  lastActivity: number;
  showInactivityWarning: boolean;

  /** Autentica contra el emisor y abre la sesión en memoria. */
  login: (email: string, password: string) => Promise<AuthUser>;
  /** Refresco silencioso; devuelve el token nuevo o `null` si la cookie no sirve. */
  refresh: () => Promise<string | null>;
  /** Cierra la sesión en memoria y avisa al emisor (best-effort). */
  logout: () => Promise<void>;
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

/**
 * Refresco en vuelo. Es la cola single-flight: cinco `401` concurrentes esperan esta misma
 * promesa y producen **una** llamada al emisor, no cinco. Se limpia al terminar para que una
 * sesión posterior pueda refrescar de nuevo.
 */
let refrescoEnVuelo: Promise<string | null> | null = null;

export const useAuthStore = create<AuthState>((set) => {
  /**
   * Único camino por el que un token se convierte en sesión (fail-closed, R-SEG-07): si el
   * payload no se puede decodificar, no se abre sesión y no se guarda nada en ninguna parte.
   */
  function establecerSesion(accessToken: string): AuthUser | null {
    const usuario = usuarioDesdeClaims(decodeJwtPayload(accessToken));
    if (!usuario) return null;

    set({
      accessToken,
      user: usuario,
      isAuthenticated: true,
      inicializando: false,
      lastActivity: Date.now(),
      showInactivityWarning: false,
    });

    return usuario;
  }

  return {
    accessToken: null,
    user: null,
    isAuthenticated: false,
    // El arranque empieza "inicializando": ProtectedRoute espera al refresco silencioso para no
    // mostrar el login como si la sesión fuera anónima.
    inicializando: true,
    tenantSlug: '',
    lastActivity: Date.now(),
    showInactivityWarning: false,

    login: async (email, password) => {
      const { accessToken } = await authService.login({ email, password });
      const usuario = establecerSesion(accessToken);

      if (!usuario) {
        // El emisor respondió 200 pero el token no es legible: la sesión no se abre.
        set({ accessToken: null, user: null, isAuthenticated: false, inicializando: false });
        throw new Error('El token de acceso emitido no se pudo decodificar.');
      }

      return usuario;
    },

    refresh: () => {
      if (refrescoEnVuelo) return refrescoEnVuelo;

      const intento = (async () => {
        try {
          const { accessToken } = await authService.refresh();
          return establecerSesion(accessToken) ? accessToken : null;
        } catch {
          return null;
        } finally {
          // Resuelto el intento —con o sin sesión— ya no hay nada que esperar en el arranque.
          set({ inicializando: false });
        }
      })();

      refrescoEnVuelo = intento.finally(() => {
        refrescoEnVuelo = null;
      });

      return refrescoEnVuelo;
    },

    logout: async () => {
      // La memoria se limpia primero: el cierre local no depende de que el emisor responda, y el
      // estado observable queda cerrado en el mismo instante de la llamada.
      set({
        accessToken: null,
        user: null,
        isAuthenticated: false,
        inicializando: false,
        tenantSlug: '',
        showInactivityWarning: false,
      });

      try {
        await authService.logout();
      } catch {
        // Best-effort: si el emisor no responde, la cookie queda pero la sesión local ya cerró.
        // No hay revocación de access tokens (ADR-012).
      }
    },

    updateActivity: () => {
      set({ lastActivity: Date.now(), showInactivityWarning: false });
    },

    setShowInactivityWarning: (show) => {
      set({ showInactivityWarning: show });
    },
  };
});

// El store es el dueño de la sesión; el cliente HTTP solo la consume. Se conecta aquí para que un
// 401 pueda refrescar una vez, repetir la petición y, si el refresco falla, cerrar la sesión y
// volver al login con el motivo explícito.
conectarSesionHttp({
  obtenerToken: () => useAuthStore.getState().accessToken,
  refrescar: () => useAuthStore.getState().refresh(),
  sesionExpirada: () => {
    void useAuthStore.getState().logout();
    window.location.href = '/login?reason=session_expired';
  },
});
