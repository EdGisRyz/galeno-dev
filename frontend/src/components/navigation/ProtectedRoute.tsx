import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth.store';
import type { UserRole } from '@/features/auth/auth.types';

interface ProtectedRouteProps {
  /** Roles con acceso a las rutas hijas (ADR-013). Sin la prop, cualquier sesión autenticada pasa. */
  allowedRoles?: UserRole[];
}

export function ProtectedRoute({ allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, user, inicializando } = useAuthStore();
  const location = useLocation();

  // Espera al refresco silencioso de arranque (ADR-011): mientras está en vuelo no se decide
  // nada, para no mostrar el login como si la sesión fuera anónima cuando solo está recargando.
  if (inicializando) {
    return null;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (allowedRoles && (!user || !allowedRoles.includes(user.role))) {
    // Provisional: `PACIENTE` todavía no tiene pantalla propia (su matriz de ADR-013 se define
    // cuando exista un contrato de paciente). Hasta entonces vuelve al login con el motivo
    // explícito, en lugar de entrar a la agenda clínica o de inventar una pantalla de paciente.
    return <Navigate to="/login?reason=sin_acceso" replace />;
  }

  return <Outlet />;
}
