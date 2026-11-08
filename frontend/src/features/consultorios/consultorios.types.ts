// Tipos TypeScript del dominio Consultorio.
// Reflejo tipado de `ConsultorioResponse` (backend): camelCase, igual que `Paciente`
// (ver pacientes.types.ts) — Jackson serializa los records del backend en camelCase por defecto.

export interface Consultorio {
  id: string;
  organizacionId: string;
  nombre: string;
  direccion?: string | null;
  telefono?: string | null;
  activo: boolean;
  createdAt: string;
}
