// Tipos TypeScript del dominio Paciente.
// Reflejo tipado de la tabla `paciente` del esquema V1.

export interface Paciente {
  id: string;
  organizacion_id: string;
  nombre: string;
  telefono?: string | null;
  fecha_nacimiento?: string | null;
  sexo?: 'M' | 'F' | 'O' | null;
  email?: string | null;
  notas?: string | null;
  activo: boolean;
  created_at: string;
}
