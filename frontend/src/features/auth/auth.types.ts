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

export interface LoginResponse {
  accessToken: string;
  // Agregar más campos cuando se defina los datos con el backend
}
