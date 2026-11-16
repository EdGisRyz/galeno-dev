// Tipos TypeScript del dominio Usuario (cuentas del personal de la organización).
// Refleja `UsuarioResponse` (backend), en camelCase, igual que Paciente/Consultorio/Medico/
// Organizacion — Jackson serializa los records del backend en camelCase por defecto.

export type RolUsuario = 'ADMIN' | 'MEDICO' | 'PACIENTE' | 'RECEPCIONISTA';

export interface Usuario {
  id: string;
  organizacionId: string;
  medicoId?: string | null;
  email: string;
  rol: RolUsuario;
  activo: boolean;
  createdAt: string;
}
