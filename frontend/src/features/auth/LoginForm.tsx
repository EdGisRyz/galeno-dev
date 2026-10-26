import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { loginSchema, type LoginFormValues } from './auth.schema';
import { authService } from './auth.service';
import { useAuthStore } from '@/store/auth.store';
import { Button, Input, Alert } from '@/components/ui';
import { Mail, Lock, LogIn, Sparkles } from 'lucide-react';

export function LoginForm() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { login } = useAuthStore();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const reason = searchParams.get('reason');

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: 'dra.elena@sanrafael.com',
      password: 'password123',
    },
  });

  const onSubmit = async (values: LoginFormValues) => {
    try {
      setIsLoading(true);
      setErrorMessage(null);
      const data = await authService.login(values);

      // Guardar sesión en store
      const anyData = data as unknown as { accessToken: string; user?: unknown };
      login(anyData.accessToken, anyData.user as never);

      // Redirigir a la agenda principal
      navigate('/agenda', { replace: true });
    } catch (err: unknown) {
      const errorObj = err as { response?: { data?: { message?: string } } };
      setErrorMessage(
        errorObj.response?.data?.message ||
          'Credenciales incorrectas o error de comunicación con el servicio de autenticación.'
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleQuickLogin = (email: string) => {
    setValue('email', email);
    setValue('password', 'password123');
  };

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-xl font-bold font-heading text-[#0B1C30]">
          Iniciar Sesión
        </h3>
        <p className="text-xs text-[#64748B] mt-1">
          Ingresa tus credenciales profesionales para acceder a la estación clínica.
        </p>
      </div>

      {/* Notificación de cierre de sesión HIPAA */}
      {reason === 'session_expired' && (
        <Alert variant="warning" title="Sesión Expirada por Inactividad (HIPAA)">
          Por protocolo de protección de datos de salud, tu sesión anterior se cerró automáticamente al superar 30 minutos sin actividad.
        </Alert>
      )}

      {errorMessage && (
        <Alert variant="error" title="Error de Autenticación">
          {errorMessage}
        </Alert>
      )}

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        <Input
          label="Correo Electrónico"
          type="email"
          placeholder="medico@clinica.com"
          leftIcon={<Mail className="w-4 h-4" />}
          error={errors.email?.message}
          {...register('email')}
          required
        />

        <Input
          label="Contraseña"
          type="password"
          placeholder="••••••••"
          leftIcon={<Lock className="w-4 h-4" />}
          error={errors.password?.message}
          {...register('password')}
          required
        />

        <div className="flex items-center justify-between text-xs pt-1">
          <label className="flex items-center gap-2 cursor-pointer select-none text-[#41484D]">
            <input
              type="checkbox"
              defaultChecked
              className="w-4 h-4 rounded border-[#CBD5E1] text-[#0A4D68] focus:ring-[#06B6D4]"
            />
            <span>Recordar estación clínica</span>
          </label>
          <a
            href="#olvide"
            onClick={(e) => {
              e.preventDefault();
              alert('Contacta al Administrador de tu Clínica para restablecer tu contraseña institucional.');
            }}
            className="text-[#088395] hover:text-[#0A4D68] font-medium"
          >
            ¿Olvidaste tu clave?
          </a>
        </div>

        <Button
          type="submit"
          variant="primary"
          className="w-full mt-2"
          isLoading={isLoading}
          rightIcon={<LogIn className="w-4 h-4" />}
        >
          Acceder al Sistema
        </Button>
      </form>

      {/* Selector Rápido de Usuarios para Desarrollo */}
      <div className="pt-4 border-t border-[#F1F5F9]">
        <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-[#64748B] mb-2">
          <Sparkles className="w-3.5 h-3.5 text-[#088395]" />
          <span>Acceso Rápido de Prueba (Mock):</span>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <button
            type="button"
            onClick={() => handleQuickLogin('dra.elena@sanrafael.com')}
            className="p-2 text-left rounded-lg border border-[#E2E8F0] hover:bg-[#F8FAFC] hover:border-[#0A4D68] transition-all cursor-pointer text-xs"
          >
            <span className="font-semibold text-[#0B1C30] block">Dra. Elena</span>
            <span className="text-[10px] text-[#64748B] block truncate">Medicina Gral.</span>
          </button>
          <button
            type="button"
            onClick={() => handleQuickLogin('dr.roberto@sanrafael.com')}
            className="p-2 text-left rounded-lg border border-[#E2E8F0] hover:bg-[#F8FAFC] hover:border-[#0A4D68] transition-all cursor-pointer text-xs"
          >
            <span className="font-semibold text-[#0B1C30] block">Dr. Roberto</span>
            <span className="text-[10px] text-[#64748B] block truncate">Pediatría</span>
          </button>
          <button
            type="button"
            onClick={() => handleQuickLogin('admin@sanrafael.com')}
            className="p-2 text-left rounded-lg border border-[#E2E8F0] hover:bg-[#F8FAFC] hover:border-[#0A4D68] transition-all cursor-pointer text-xs"
          >
            <span className="font-semibold text-[#0B1C30] block">Administrador</span>
            <span className="text-[10px] text-[#64748B] block truncate">Clínica Admin</span>
          </button>
        </div>
      </div>
    </div>
  );
}