// Tipos TypeScript del dominio Notificación.
// Reflejo tipado de la tabla `notificacion` del esquema V1.

export interface Notificacion {
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
