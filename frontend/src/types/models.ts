// Modelos TypeScript de Galeno SaaS
// Reflejo tipado del esquema de base de datos V1 y extensiones NOM-004 / NOM-024 (V7)

export type PlanSaaS = 'SOLO' | 'CLINICA' | 'ENTERPRISE';

export interface Organization {
  id: string;
  nombre: string;
  plan: PlanSaaS;
  subscription_id?: string | null;
  trial_hasta?: string | null;
  activo: boolean;
  created_at: string;
}

export interface TenantConfig {
  id: string;
  organizacion_id: string;
  notif_api_key?: string | null;
  storage_api_key?: string | null;
  payment_api_key?: string | null;
  audit_api_key?: string | null;
  whatsapp_conectado: boolean;
  whatsapp_numero?: string | null;
  created_at: string;
}

export interface ClinicRoom {
  id: string;
  organizacion_id: string;
  nombre: string;
  direccion?: string | null;
  telefono?: string | null;
  activo: boolean;
  created_at: string;
}

export interface Doctor {
  id: string;
  organizacion_id: string;
  consultorio_id: string;
  nombre: string;
  especialidad?: string | null;
  cedula?: string | null;
  telefono?: string | null;
  tarifa_base?: number | null;
  duracion_consulta_min?: number;
  activo: boolean;
}

export interface DoctorSchedule {
  id: string;
  medico_id: string;
  dia_semana: number; // 0=Domingo, 1=Lunes, ..., 6=Sábado
  hora_inicio: string; // HH:mm:ss
  hora_fin: string; // HH:mm:ss
  duracion_consulta: 20 | 30 | 45 | 60;
}

export interface Patient {
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

// NOM-004: Ficha de identificación extendida
export interface PatientIdentification {
  id: string;
  paciente_id: string;
  curp?: string | null;
  ocupacion?: string | null;
  domicilio_completo?: string | null;
  nombre_responsable?: string | null;
  telefono_responsable?: string | null;
}

export type UserRole = 'ADMIN' | 'MEDICO' | 'PACIENTE';

export interface User {
  id: string;
  organizacion_id: string;
  medico_id?: string | null;
  email: string;
  rol: UserRole;
  activo: boolean;
  created_at: string;
}

export type AppointmentStatus =
  | 'SIN_CONFIRMAR'
  | 'CONFIRMADA'
  | 'CANCELADA'
  | 'REAGENDADA'
  | 'NO_ASISTIO';

export type CancelledBy = 'PACIENTE' | 'ADMIN' | 'MEDICO';

export interface Appointment {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  consultorio_id: string;
  fecha_hora: string; // ISO 8601
  duracion_min: number;
  estado: AppointmentStatus;
  motivo?: string | null;
  cancelada_en?: string | null;
  cancelada_por?: CancelledBy | null;
  created_at: string;
  // Campos embebidos para comodidad de visualización en la UI
  paciente?: Patient;
  medico?: Doctor;
  consultorio?: ClinicRoom;
  pago?: Payment;
}

export type PaymentMethod = 'EFECTIVO' | 'TRANSFERENCIA' | 'TARJETA';
export type PaymentStatus = 'PAGADO' | 'PENDIENTE';

export interface Payment {
  id: string;
  organizacion_id: string;
  cita_id: string;
  monto: number;
  metodo: PaymentMethod;
  concepto?: string | null;
  estado: PaymentStatus;
  referencia?: string | null;
  pagado_en?: string | null;
  created_at: string;
}

export interface MedicalAttachment {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  cita_id?: string | null;
  subido_por: string;
  tipo: string;
  nombre_archivo: string;
  storage_file_id: string;
  mime_type?: string | null;
  notificar: boolean;
  notificado_en?: string | null;
  created_at: string;
}

export interface NotificationLog {
  id: string;
  organizacion_id: string;
  cita_id: string;
  adjunto_id?: string | null;
  canal: 'WHATSAPP';
  tipo: 'RECORDATORIO_48H' | 'RECORDATORIO_24H' | 'ADJUNTO' | 'CANCELACION_PACIENTE';
  estado: 'PENDIENTE' | 'ENVIADO' | 'FALLIDO';
  enviada_en?: string | null;
  respuesta?: string | null;
  created_at: string;
}

// ─────────────────────────────────────────
// EXPEDIENTE CLÍNICO DIGITAL (NOM-004 / NOM-024)
// ─────────────────────────────────────────

export interface MedicalHistory {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  created_at: string;
}

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

export interface NoteCorrection {
  id: string;
  nota_evolucion_id: string;
  medico_id: string;
  texto: string;
  created_at: string;
}

export type DiagnosisType = 'PRINCIPAL' | 'SECUNDARIO' | 'PRESUNTIVO';
export type DiagnosisStatus = 'ACTIVO' | 'RESUELTO' | 'CRONICO';

export interface Diagnosis {
  id: string;
  cita_id?: string | null;
  organizacion_id?: string | null;
  paciente_id: string;
  nota_evolucion_id?: string | null;
  codigo_cie10: string;
  descripcion: string;
  descripcion_cie10?: string | null;
  tipo: DiagnosisType;
  estado: DiagnosisStatus;
  fecha_diagnostico?: string | null;
  fecha_resolucion?: string | null;
  created_at: string;
}

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
