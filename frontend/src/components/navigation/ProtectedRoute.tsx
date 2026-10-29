import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth.store';
import type { UserRole } from '@/features/auth/auth.types';

interface ProtectedRouteProps {
  allowedRoles?: UserRole[];
}

export function ProtectedRoute({ allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, user } = useAuthStore();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (allowedRoles && user && !allowedRoles.includes(user.role)) {
    return <Navigate to="/agenda" replace />;
  }

  return <Outlet />;
}

