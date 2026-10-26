import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from './layouts/MainLayout';
import { AuthLayout } from './layouts/AuthLayout';
import { LoginForm } from './features/auth/LoginForm';
import { AgendaPage } from './features/agenda/AgendaPage';
import { ProtectedRoute } from './components/navigation/ProtectedRoute';

function App() {
  return (
    <Routes>
      {/* Rutas públicas de autenticación */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginForm />} />
      </Route>

      {/* Rutas protegidas de la estación clínica */}
      <Route element={<ProtectedRoute />}>
        <Route element={<MainLayout />}>
          <Route path="/" element={<Navigate to="/agenda" replace />} />
          <Route path="/agenda" element={<AgendaPage />} />
          {/* Próximos módulos: /pacientes, /expediente, /cobros, /administracion */}
          <Route path="*" element={<Navigate to="/agenda" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}
export default App;