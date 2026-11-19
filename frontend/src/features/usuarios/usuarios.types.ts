// Tipos TypeScript del dominio Usuario (cuentas del personal de la organización).
// Refleja `UsuarioResponse` (backend), en camelCase, igual que Paciente/Consultorio/Medico/
// Organizacion — Jackson serializa los records del backend en camelCase por defecto.

export type RolUsuario = 'ADMIN' | 'MEDICO' | 'PACIENTE' | 'RECEPCIONISTA';

export interface Usuario {
  id: string;
  organizacionId: string;
  medicoId?: string | null;
  /**
   * Nombre de la persona. Nulo en las cuentas anteriores a la V10 y opcional en el tipo porque el
   * contrato lo admite: la lista muestra el correo cuando no hay nombre, sin inventarlo.
   */
  nombre?: string | null;
  /** Teléfono de contacto. Nulo en las cuentas anteriores y opcional en el alta. */
  telefono?: string | null;
  email: string;
  rol: RolUsuario;
  activo: boolean;
  createdAt: string;
}
