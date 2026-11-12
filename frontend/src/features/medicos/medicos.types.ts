// Tipos TypeScript del dominio Médico.
// Reflejo tipado de `MedicoResponse` y `HorarioMedicoResponse` (backend), en camelCase, igual que
// `Paciente` y `Consultorio` (ver pacientes.types.ts) — Jackson serializa los records del backend
// en camelCase por defecto. `MedicoResponse` no incluye `createdAt` (la tabla `medico` de V1 no
// tiene esa columna) ni una duración de consulta propia: eso vive por bloque en `HorarioMedico`.

export interface Medico {
  id: string;
  organizacionId: string;
  consultorioId: string;
  nombre: string;
  especialidad?: string | null;
  cedula?: string | null;
  telefono?: string | null;
  tarifaBase?: number | null;
  activo: boolean;
}

export interface HorarioMedico {
  id: string;
  medicoId: string;
  diaSemana: number; // 0=Domingo, 1=Lunes, ..., 6=Sábado (convención Date.getDay())
  horaInicio: string; // HH:mm:ss
  horaFin: string; // HH:mm:ss
  duracionConsulta: 20 | 30 | 45 | 60;
}
