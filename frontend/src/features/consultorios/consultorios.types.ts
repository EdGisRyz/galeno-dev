// Tipos TypeScript del dominio Consultorio.
// Reflejo tipado de la tabla `consultorio` del esquema V1.

export interface Consultorio {
  id: string;
  organizacion_id: string;
  nombre: string;
  direccion?: string | null;
  telefono?: string | null;
  activo: boolean;
  created_at: string;
}
