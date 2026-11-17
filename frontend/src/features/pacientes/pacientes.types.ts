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
  //
  // Ficha de identificación (NOM-004) en **campos planos**, tal como la devuelve
  // `PacienteResponse` (spec S6): al mismo nivel que el resto y en camelCase. Antes la pantalla
  // enviaba y leía un objeto anidado `ficha` que el backend no declaraba, así que el dato se
  // descartaba en silencio; ese objeto ya no existe en el cliente.
  //
  // `curp` es opcional porque un paciente puede registrarse sin ella (urgencia, menor o
  // extranjero), pero cuando viene es única dentro de la organización y se guarda canónica.
  curp?: string | null;
  ocupacion?: string | null;
  domicilio?: string | null;
  nombreResponsable?: string | null;
  telefonoResponsable?: string | null;
  parentesco?: string | null;
}
