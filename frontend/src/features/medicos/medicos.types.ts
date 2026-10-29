// Tipos TypeScript del dominio Médico.
// Reflejo tipado de las tablas `medico` y `horario_medico` del esquema V1.

export interface Medico {
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

export interface HorarioMedico {
  id: string;
  medico_id: string;
  dia_semana: number; // 0=Domingo, 1=Lunes, ..., 6=Sábado
  hora_inicio: string; // HH:mm:ss
  hora_fin: string; // HH:mm:ss
  duracion_consulta: 20 | 30 | 45 | 60;
}
