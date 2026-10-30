// ─────────────────────────────────────────
// TIPOS EN CUARENTENA — NO EXISTEN EN EL ESQUEMA V1
// ─────────────────────────────────────────
// Estos tipos NO tienen tabla ni contrato en `V1__base_schema.sql` y el backend los declaró
// fuera de alcance (SOAP y prescripciones son trabajo futuro; la ficha de identificación
// extendida y la historia clínica son NOM-004 / NOM-024, posteriores a V1).
//
// Nadie debe importarlos como si fueran un contrato real: hoy solo los consumen los mocks
// (`src/lib/mock/mockData.ts`, `src/lib/mock/mockAdapter.ts`) para poder dibujar pantallas
// que todavía no tienen backend. Cuando exista la migración que los cree, cada uno se mueve a
// `src/features/<dominio>/<dominio>.types.ts` con el nombre del contrato real.
//
// No se borran: son la referencia de la forma que la UI mockeada ya espera.

// NOM-004: ficha de identificación extendida. No hay tabla en V1.
export interface PatientIdentification {
  id: string;
  paciente_id: string;
  curp?: string | null;
  ocupacion?: string | null;
  domicilio_completo?: string | null;
  nombre_responsable?: string | null;
  telefono_responsable?: string | null;
}

// NOM-004: historia clínica. No hay tabla en V1.
export interface MedicalHistory {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  created_at: string;
}

// NOM-004: interrogatorio. No hay tabla en V1.
export interface Interrogatory {
  id: string;
  historia_clinica_id: string;
  motivo_consulta?: string | null;
  antecedentes_heredo_familiares?: string | null;
  antecedentes_personales_no_patologicos?: string | null;
  antecedentes_personales_patologicos?: string | null;
  padecimiento_actual?: string | null;
  created_at: string;
}

// NOM-004: exploración física. No hay tabla en V1.
export interface PhysicalExam {
  id: string;
  historia_clinica_id: string;
  temperatura?: number | null;
  presion_arterial_sistolica?: number | null;
  presion_arterial_diastolica?: number | null;
  frecuencia_cardiaca?: number | null;
  frecuencia_respiratoria?: number | null;
  peso?: number | null; // kg
  talla?: number | null; // m
  imc?: number | null; // calculado
  observaciones?: string | null;
  created_at: string;
}

// NOM-004: nota SOAP. No hay tabla en V1; el backend declaró SOAP fuera de alcance.
export interface SoapNote {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  cita_id?: string | null;
  subjetivo: string; // S: Qué refiere el paciente
  objetivo: string; // O: Hallazgos físicos
  signos_vitales?: {
    temperatura?: number;
    presion?: string;
    fc?: number;
    fr?: number;
    peso?: number;
    talla?: number;
    imc?: number;
  } | null;
  analisis: string; // A: Interpretación clínica y diagnóstico
  plan: string; // P: Tratamiento e indicaciones
  inmutable: boolean; // TRUE una vez cerrada (NOM-004)
  created_at: string;
  correcciones?: NoteCorrection[];
}

// NOM-004: corrección de nota cerrada. No hay tabla en V1.
export interface NoteCorrection {
  id: string;
  nota_evolucion_id: string;
  medico_id: string;
  texto: string;
  created_at: string;
}

// Prescripción. No hay tabla en V1; el backend declaró prescripciones fuera de alcance.
export interface Prescription {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  nota_evolucion_id?: string | null;
  medicamento: string;
  nombre_comercial?: string | null;
  dosis: string;
  via_administracion?: string | null;
  frecuencia: string;
  duracion: string;
  instrucciones?: string | null;
  activo: boolean;
  created_at: string;
}
