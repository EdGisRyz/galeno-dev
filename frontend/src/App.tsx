import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from './layouts/MainLayout';
import { AuthLayout } from './layouts/AuthLayout';
import { LoginForm } from './features/auth/LoginForm';
import { AgendaPage } from './features/agenda/AgendaPage';
import { PacientesPage } from './features/pacientes/PacientesPage';
import { ProtectedRoute } from './components/navigation/ProtectedRoute';
import { useAuthStore } from './store/auth.store';

function App() {
  const refresh = useAuthStore((state) => state.refresh);

  // Refresco silencioso de arranque (ADR-011): recupera la sesión desde la cookie `httpOnly` sin
  // pedir credenciales. `refresh()` es single-flight, así que el doble efecto de StrictMode
  // produce una sola llamada al emisor. Si falla, la sesión queda anónima y ProtectedRoute
  // redirige al login: no hay reintento en bucle.
  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <Routes>
      {/* Rutas públicas de autenticación */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginForm />} />
      </Route>

      {/* Rutas protegidas de la estación clínica. ADR-013: la agenda es de `ADMIN` y `MEDICO`;
          `PACIENTE` no tiene pantalla todavía y ProtectedRoute lo devuelve al login con
          `reason=sin_acceso`. */}
      <Route element={<ProtectedRoute allowedRoles={['ADMIN', 'MEDICO']} />}>
        <Route element={<MainLayout />}>
          <Route path="/" element={<Navigate to="/agenda" replace />} />
          <Route path="/agenda" element={<AgendaPage />} />
          <Route path="/pacientes" element={<PacientesPage />} />
          {/* Próximos módulos: /expediente, /cobros, /administracion */}
          <Route path="*" element={<Navigate to="/agenda" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
export default App;
