import { useEffect } from 'react';
import { useAuthStore } from '@/store/auth.store';

const WARNING_MS = 28 * 60 * 1000; // 28 minutos: advertencia preventiva
const TIMEOUT_MS = 30 * 60 * 1000; // 30 minutos: cierre de sesión obligatorio HIPAA

/**
 * Hook global de cumplimiento HIPAA para el control de inactividad de sesión.
 * Si el usuario pasa 28 minutos sin interacción, activa un modal preventivo.
 * A los 30 minutos, invalida la sesión y redirige al login con motivo session_expired.
 */
export function useInactivityTimeout() {
  const { isAuthenticated, lastActivity, updateActivity, setShowInactivityWarning, logout } =
    useAuthStore();

  useEffect(() => {
    if (!isAuthenticated) return;

    // Escucha de eventos de interacción física del usuario
    const handleUserInteraction = () => {
      // Actualizar solo si pasaron al menos 15 segundos para no saturar el store
      if (Date.now() - lastActivity > 15000) {
        updateActivity();
      }
    };

    window.addEventListener('mousemove', handleUserInteraction, { passive: true });
    window.addEventListener('keydown', handleUserInteraction, { passive: true });
    window.addEventListener('click', handleUserInteraction, { passive: true });
    window.addEventListener('touchstart', handleUserInteraction, { passive: true });

    // Intervalo de comprobación cada 15 segundos
    const interval = setInterval(() => {
      const elapsed = Date.now() - useAuthStore.getState().lastActivity;

      if (elapsed >= TIMEOUT_MS) {
        logout();
        window.location.href = '/login?reason=session_expired';
      } else if (elapsed >= WARNING_MS) {
        setShowInactivityWarning(true);
      }
    }, 15000);

    return () => {
      window.removeEventListener('mousemove', handleUserInteraction);
      window.removeEventListener('keydown', handleUserInteraction);
      window.removeEventListener('click', handleUserInteraction);
      window.removeEventListener('touchstart', handleUserInteraction);
      clearInterval(interval);
    };
  }, [isAuthenticated, lastActivity, updateActivity, setShowInactivityWarning, logout]);
}

