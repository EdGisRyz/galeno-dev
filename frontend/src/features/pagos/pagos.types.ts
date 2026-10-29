// Tipos TypeScript del dominio Pago.
// Reflejo tipado de la tabla `pago` del esquema V1.

export type MetodoPago = 'EFECTIVO' | 'TRANSFERENCIA' | 'TARJETA';
export type EstadoPago = 'PAGADO' | 'PENDIENTE';

export interface Pago {
  id: string;
  organizacion_id: string;
  cita_id: string;
  monto: number;
  metodo: MetodoPago;
  concepto?: string | null;
  estado: EstadoPago;
  referencia?: string | null;
  pagado_en?: string | null;
  created_at: string;
}
