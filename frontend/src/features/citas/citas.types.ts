// Tipos TypeScript del dominio Cita.
// Reflejo tipado de la tabla `cita` del esquema V1.

import type { Consultorio } from '@/features/consultorios/consultorios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import type { Pago } from '@/features/pagos/pagos.types';

export type EstadoCita =
  | 'SIN_CONFIRMAR'
  | 'CONFIRMADA'
  | 'CANCELADA'
  | 'REAGENDADA'
  | 'NO_ASISTIO';

export type CanceladaPor = 'PACIENTE' | 'ADMIN' | 'MEDICO';

export interface Cita {
  id: string;
  organizacion_id: string;
  paciente_id: string;
  medico_id: string;
  consultorio_id: string;
  fecha_hora: string; // ISO 8601
  duracion_min: number;
  estado: EstadoCita;
  motivo?: string | null;
  cancelada_en?: string | null;
  cancelada_por?: CanceladaPor | null;
  created_at: string;
  // Campos embebidos para comodidad de visualización en la UI
  paciente?: Paciente;
  medico?: Medico;
  consultorio?: Consultorio;
  pago?: Pago;
}
