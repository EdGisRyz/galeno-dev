// Tipos TypeScript del dominio Adjunto.
// Reflejo tipado de la tabla `adjunto` del esquema V1.

export interface Adjunto {
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
