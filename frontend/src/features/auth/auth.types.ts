// Tipos TypeScript del dominio Auth
// `auth` se conserva en inglés: es una preocupación técnica, no una entidad del dominio
// (ADR / plan §4.3, excepción declarada).
// Reflejo tipado de la tabla `usuario` del esquema V1 y de los claims del token.

export type UserRole = 'ADMIN' | 'MEDICO' | 'PACIENTE';

// Proyección de la tabla `usuario` del esquema V1.
export interface Usuario {
  id: string;
  organizacion_id: string;
  medico_id?: string | null;
  email: string;
  rol: UserRole;
  activo: boolean;
  created_at: string;
}

// Sesión del cliente: proyección de los claims del token (`sub`, `email`, `role`, `tenantId`,
// `medicoId`) más los datos de presentación que la UI guarda junto al usuario.
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

export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * Proyección de identidad que el emisor devuelve junto al token: son los claims del token
 * (ADR-010), no la fila `usuario` del esquema. Se tipa para no mentir sobre la forma del
 * contrato, pero la autoridad de la sesión son los claims del token que se decodifican en el
 * store: si el cuerpo y el token no coincidieran, manda el token.
 */
export interface UsuarioSesion {
  sub: string;
  email: string;
  role: UserRole;
  tenantId: string;
  medicoId?: string | null;
}

/** `POST /auth/login` del emisor: token de acceso más la proyección del usuario. */
export interface LoginResponse {
  accessToken: string;
  user?: UsuarioSesion;
}

/** `POST /auth/refresh` del emisor: token de acceso renovado (el refresco viaja en la cookie). */
export interface RefreshResponse {
  accessToken: string;
}
