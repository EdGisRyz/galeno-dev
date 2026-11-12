// Tipos TypeScript del dominio Organización.
// `Organizacion` refleja `OrganizacionResponse` (backend), en camelCase, igual que `Paciente`
// (ver pacientes.types.ts). `ConfiguracionTenant` sigue en cuarentena: no tiene controller real.

export type PlanSaaS = 'SOLO' | 'CLINICA' | 'ENTERPRISE';

export interface Organizacion {
  id: string;
  nombre: string;
  plan: PlanSaaS;
  subscriptionId?: string | null;
  trialHasta?: string | null;
  activo: boolean;
  createdAt: string;
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
