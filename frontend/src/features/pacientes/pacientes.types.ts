// Tipos TypeScript del dominio Paciente.
// Reflejo tipado de `PacienteResponse` (backend), no de las columnas SQL: Jackson serializa los
// records del backend en camelCase por defecto (sin naming-strategy configurado) y ese es el JSON
// real que viaja por la red — confirmado contra las pruebas del propio backend
// (`jsonPath("$.organizacionId")`, `jsonPath("$.fechaNacimiento")`). Usar snake_case aquí hacía que
// PacientesPage leyera `undefined` en producción aunque compilara limpio.

export interface Paciente {
  id: string;
  organizacionId: string;
  nombre: string;
  telefono?: string | null;
  fechaNacimiento?: string | null;
  sexo?: 'M' | 'F' | 'O' | null;
  email?: string | null;
  notas?: string | null;
  activo: boolean;
  createdAt: string;
}
