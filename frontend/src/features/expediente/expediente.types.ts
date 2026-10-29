// Tipos TypeScript del dominio Expediente clínico.
// Reflejo tipado de la tabla `diagnostico` del esquema V1.

export type TipoDiagnostico = 'PRINCIPAL' | 'SECUNDARIO' | 'PRESUNTIVO';
export type EstadoDiagnostico = 'ACTIVO' | 'RESUELTO' | 'CRONICO';

export interface Diagnostico {
  id: string;
  cita_id?: string | null;
  organizacion_id?: string | null;
  paciente_id: string;
  nota_evolucion_id?: string | null;
  codigo_cie10: string;
  descripcion: string;
  descripcion_cie10?: string | null;
  tipo: TipoDiagnostico;
  estado: EstadoDiagnostico;
  fecha_diagnostico?: string | null;
  fecha_resolucion?: string | null;
  created_at: string;
}
