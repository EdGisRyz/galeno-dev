import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from './layouts/MainLayout';
import { AuthLayout } from './layouts/AuthLayout';
import { LoginForm } from './features/auth/LoginForm';
import { AgendaPage } from './features/agenda/AgendaPage';
import { PacientesPage } from './features/pacientes/PacientesPage';
import { VitalixHomePage } from './features/home/VitalixHomePage';
import { RegistroClinicaPage } from './features/registro/RegistroClinicaPage';
import { MarcaClinicaPage } from './features/administracion/MarcaClinicaPage';
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
      {/* Landing pública de la plataforma: visible con o sin sesión (plan §2, cambio de alcance). */}
      <Route path="/" element={<VitalixHomePage />} />

      {/* Registro público de clínicas (onboarding). Solo habla con el mock: el backend real
          bloquea POST /organizaciones a propósito hasta que exista el flujo de Sprint 2. */}
      <Route path="/registro" element={<RegistroClinicaPage />} />

      {/* Rutas públicas de autenticación */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginForm />} />
      </Route>

      {/* Rutas protegidas de la estación clínica. ADR-013: la agenda es de `ADMIN` y `MEDICO`;
          `PACIENTE` no tiene pantalla todavía y ProtectedRoute lo devuelve al login con
          `reason=sin_acceso`. */}
      <Route element={<ProtectedRoute allowedRoles={['ADMIN', 'MEDICO']} />}>
        <Route element={<MainLayout />}>
          <Route path="/agenda" element={<AgendaPage />} />
          <Route path="/pacientes" element={<PacientesPage />} />

          {/* Marca de la clínica: solo ADMIN. ProtectedRoute anidado porque el grupo exterior ya
              admite ADMIN y MEDICO; aquí se acota más. */}
          <Route element={<ProtectedRoute allowedRoles={['ADMIN']} />}>
            <Route path="/administracion/marca" element={<MarcaClinicaPage />} />
          </Route>

          {/* Próximos módulos: /expediente, /cobros */}
          <Route path="*" element={<Navigate to="/agenda" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
export default App;
