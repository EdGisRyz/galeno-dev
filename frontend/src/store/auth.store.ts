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
   * Slug/token del tenant de la sesión. Se deriva del claim `tenantId` al abrir la sesión y **nunca**
   * se rellena con un valor por defecto (GAL-Front001 §2, guardarraíl 4): si no hay claim, no hay
   * sesión.
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
 * Claims del token que la sesión consume (ADR-010).
 * `exp` se guarda tal como llega: su presencia y su tipo forman parte del contrato, así que la
 * validación decide si sirve, en lugar de que la proyección lo dé por bueno.
 */
interface ClaimsToken {
  id?: string;
  email?: string;
  role?: UserRole;
  tenantId?: string;
  medicoId?: string;
  /** Vencimiento en segundos epoch (RFC 7519 §2). Crudo a propósito: puede faltar o no ser número. */
  exp?: unknown;
}

/**
 * Decodificador seguro de payload JWT en navegador: base64url + UTF-8.
 * Devuelve `null` ante cualquier entrada mal formada; nunca lanza.
 */
function decodeJwtPayload(token: string): ClaimsToken | null {
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
      exp: payload.exp,
    };
  } catch {
    return null;
  }
}

/** Motivo por el que un token no puede abrir sesión (R-SEG-07, fail-closed). */
type MotivoRechazo = 'malformado' | 'identidad_incompleta' | 'sin_tenant' | 'sin_vencimiento' | 'vencido';

/** Resultado de intentar convertir un token en sesión: usuario, o el motivo del rechazo. */
type SesionResuelta = { ok: true; usuario: AuthUser } | { ok: false; motivo: MotivoRechazo };

/** Mensaje explícito por motivo: un rechazo se ve, no se silencia (plan §2 guardarraíl 4). */
const MENSAJE_RECHAZO: Record<MotivoRechazo, string> = {
  malformado: 'El token de acceso emitido no se pudo decodificar.',
  identidad_incompleta: 'El token de acceso no trae la identidad completa (sub, email, role).',
  sin_tenant: 'El token de acceso no trae el claim obligatorio tenantId.',
  sin_vencimiento: 'El token de acceso no trae el claim obligatorio exp (ADR-010).',
  vencido: 'El token de acceso está vencido: exp quedó en el pasado (ADR-012).',
};

/** Ahora en segundos epoch: la unidad de `exp` según RFC 7519 §2. */
function ahoraEnSegundos(): number {
  return Math.floor(Date.now() / 1000);
}

/**
 * Proyecta los claims a la sesión de la UI, o explica por qué no puede hacerlo.
 *
 * Fail-closed (R-SEG-07, ADR-010, ADR-012, ADR-013): sin identidad completa, sin `tenantId` o
 * sin un `exp` numérico vigente no hay usuario. El tenant **jamás** se rellena con un valor por
 * defecto: el claim es la autoridad y un token sin él se rechaza (plan §2, guardarraíl 4).
 */
function resolverSesion(claims: ClaimsToken | null): SesionResuelta {
  if (!claims) return { ok: false, motivo: 'malformado' };

  const { id, email, role, tenantId, medicoId, exp } = claims;

  if (!id || !email || !role) return { ok: false, motivo: 'identidad_incompleta' };
  if (!tenantId) return { ok: false, motivo: 'sin_tenant' };
  if (typeof exp !== 'number' || !Number.isFinite(exp)) {
    return { ok: false, motivo: 'sin_vencimiento' };
  }
  if (exp <= ahoraEnSegundos()) return { ok: false, motivo: 'vencido' };

  return { ok: true, usuario: { id, email, role, tenantId, medicoId } };
}

/**
 * Estado observable de una sesión cerrada. Un solo lugar define su forma, para que login
 * rechazado, refresco inválido y cierre de sesión no limpien cosas distintas.
 */
const SESION_CERRADA = {
  accessToken: null,
  user: null,
  isAuthenticated: false,
  inicializando: false,
  tenantSlug: '',
  showInactivityWarning: false,
} as const;

/**
 * Refresco en vuelo. Es la cola single-flight: cinco `401` concurrentes esperan esta misma
 * promesa y producen **una** llamada al emisor, no cinco. Se limpia al terminar para que una
 * sesión posterior pueda refrescar de nuevo.
 */
let refrescoEnVuelo: Promise<string | null> | null = null;

export const useAuthStore = create<AuthState>((set) => {
  /**
   * Único camino por el que un token se convierte en sesión (fail-closed, R-SEG-07): si el
   * payload no se puede decodificar, si le falta la identidad, el tenant o un `exp` vigente, no se
   * abre sesión, se limpia la memoria y se devuelve el motivo. Login y refresco de arranque pasan
   * por aquí, así que ninguno puede saltarse la comprobación de vigencia (ADR-010, ADR-012).
   */
  function establecerSesion(accessToken: string): SesionResuelta {
    const resultado = resolverSesion(decodeJwtPayload(accessToken));

    if (!resultado.ok) {
      set(SESION_CERRADA);
      return resultado;
    }

    const usuario = resultado.usuario;
    set({
      accessToken,
      user: usuario,
      isAuthenticated: true,
      inicializando: false,
      // El tenant de la sesión sale del claim `tenantId`: no se inventa un nombre de clínica.
      tenantSlug: usuario.tenantId,
      lastActivity: Date.now(),
      showInactivityWarning: false,
    });

    return resultado;
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
      const resultado = establecerSesion(accessToken);

      if (!resultado.ok) {
        // El emisor respondió 200 pero el token no sirve para abrir sesión: falla visible.
        throw new Error(MENSAJE_RECHAZO[resultado.motivo]);
      }

      return resultado.usuario;
    },

    refresh: () => {
      if (refrescoEnVuelo) return refrescoEnVuelo;

      const intento = (async () => {
        try {
          const { accessToken } = await authService.refresh();
          // El refresco usa la misma puerta que el login: un token vencido o sin `exp` deja la
          // sesión anónima en lugar de renovarla a ciegas (ADR-012).
          return establecerSesion(accessToken).ok ? accessToken : null;
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
      set(SESION_CERRADA);

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
