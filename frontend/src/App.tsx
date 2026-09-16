import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from './layouts/MainLayout';
import { AuthLayout } from './layouts/AuthLayout';
import { LoginForm } from './features/auth/LoginForm';

function App() {
  return (
    <Routes>
      {/* Rutas protegidas con navbar */}
      <Route element={<MainLayout />}>
        <Route path="/" element={<Navigate to="/login" replace />} />
        {/* Agregar más rutas cuando se implementen features */}
      </Route>

      {/* Rutas de autenticación */}
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginForm />} />
        {/* Agregar registro cuando se necesite */}
      </Route>
    </Routes>
  );
}

export default App;