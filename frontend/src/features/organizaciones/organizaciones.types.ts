// Tipos TypeScript del dominio Organización.
// Reflejo tipado de las tablas `organizacion` y `configuracion_tenant` del esquema V1.

export type PlanSaaS = 'SOLO' | 'CLINICA' | 'ENTERPRISE';

export interface Organizacion {
  id: string;
  nombre: string;
  plan: PlanSaaS;
  subscription_id?: string | null;
  trial_hasta?: string | null;
  activo: boolean;
  created_at: string;
}

export interface ConfiguracionTenant {
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
