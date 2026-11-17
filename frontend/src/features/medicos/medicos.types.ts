// Tipos TypeScript del dominio Médico.
// Reflejo tipado de `MedicoResponse` y `HorarioMedicoResponse` (backend), en camelCase, igual que
// `Paciente` y `Consultorio` (ver pacientes.types.ts) — Jackson serializa los records del backend
// en camelCase por defecto. `MedicoResponse` no incluye `createdAt` (la tabla `medico` de V1 no
// tiene esa columna) ni una duración de consulta propia: eso vive por bloque en `HorarioMedico`.
//
// Un consultorio asignado a un médico no necesita un tipo propio: `GET /medicos/{id}/consultorios`
// devuelve `ConsultorioResponse`, que ya está tipado como `Consultorio` en
// `features/consultorios/consultorios.types.ts`. Duplicarlo obligaría a mantener dos formas del
// mismo contrato.

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
  // Consultorio donde se dicta el bloque: el bloque es la unidad que responde dónde atiende el
  // médico cada día, así que el consultorio pertenece al bloque y no al médico.
  consultorioId: string;
  // Nombre del consultorio, para pintarlo sin una petición extra (el backend lo incluye en el DTO).
  consultorioNombre: string;
  diaSemana: number; // 0=Domingo, 1=Lunes, ..., 6=Sábado (convención Date.getDay())
  horaInicio: string; // HH:mm:ss
  horaFin: string; // HH:mm:ss
  duracionConsulta: 20 | 30 | 45 | 60;
}
