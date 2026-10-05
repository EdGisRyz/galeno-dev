import { useAuthStore } from '@/store/auth.store';
import { Modal, Button, Alert } from '@/components/ui';
import { ShieldAlert, Clock } from 'lucide-react';

export function InactivityWarningModal() {
  const { showInactivityWarning, updateActivity, logout } = useAuthStore();

  if (!showInactivityWarning) return null;

  return (
    <Modal
      isOpen={showInactivityWarning}
      onClose={updateActivity}
      title="Advertencia de Inactividad"
      description="Protocolo de seguridad clínica HIPAA / NOM-024"
      maxWidth="md"
    >
      <div className="space-y-4">
        <Alert variant="warning" title="Cierre de Sesión Automático en Breve">
          Por motivos de seguridad y resguardo de datos clínicos confidenciales, tu sesión se cerrará automáticamente en menos de 2 minutos si no detecta actividad.
        </Alert>

        <div className="flex items-center gap-3 p-3.5 bg-[#F8FAFC] border border-[#E2E8F0] rounded-xl text-xs text-[#41484D]">
          <Clock className="w-5 h-5 text-[#0A4D68] shrink-0" />
          <span>
            Para continuar trabajando en el expediente y la agenda sin interrupciones, confirma que sigues en tu estación clínica.
          </span>
        </div>

        <div className="flex justify-end gap-2.5 pt-3 border-t border-[#F1F5F9]">
          <Button variant="outline" size="sm" onClick={logout}>
            Cerrar Sesión Ahora
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={updateActivity}
            leftIcon={<ShieldAlert className="w-4 h-4 text-[#88BDDC]" />}
          >
            Mantener Sesión Activa
          </Button>
        </div>
      </div>
    </Modal>
  );
}

