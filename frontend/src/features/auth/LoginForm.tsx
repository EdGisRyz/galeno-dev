import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { loginSchema, type LoginFormValues } from './auth.schema';
import { useAuthStore } from '@/store/auth.store';
import { Button, Input, Alert } from '@/components/ui';
import { Mail, Lock, LogIn } from 'lucide-react';

export function LoginForm() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { login } = useAuthStore();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const reason = searchParams.get('reason');

  // Sin `defaultValues` con credenciales: el formulario arranca vacío (plan sección 2 guardarraíl 5).
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
  });

  const onSubmit = async (values: LoginFormValues) => {
    try {
      setIsLoading(true);
      setErrorMessage(null);

      // El store autentica contra el emisor y deja la sesión **solo en memoria** (ADR-011).
      await login(values.email, values.password);

      // Redirigir al módulo clínico real (agenda sigue en mock; ver H5 de la revisión técnica)
      navigate('/pacientes', { replace: true });
    } catch (err: unknown) {
      // El `ErrorResponse` del backend y el emisor de desarrollo usan `mensaje`; leer `message`
      // dejaba siempre el texto genérico y ocultaba el motivo real al usuario.
      const errorObj = err as { response?: { data?: { mensaje?: string } } };
      setErrorMessage(
        errorObj.response?.data?.mensaje ||
          'Credenciales incorrectas o error de comunicación con el servicio de autenticación.'
      );
    } finally {
      setIsLoading(false);
    }
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

      {/* Notificación de cierre de sesión por inactividad */}
      {reason === 'session_expired' && (
        <Alert variant="warning" title="Sesión Expirada por Inactividad (NOM-024)">
          Por protocolo de protección de datos de salud, tu sesión anterior se cerró automáticamente al superar 30 minutos sin actividad.
        </Alert>
      )}

      {/* Rechazo por rol: la guarda devuelve acá a quien no tiene ninguna sección habilitada.
          Sin este aviso la persona vuelve al login como si se hubiera equivocado de contraseña,
          y el camino de salida queda invisible. El texto nombra las dos causas posibles —un rol
          sin secciones y una cuenta de paciente, cuyo rol es correcto pero no tiene portal—
          sin afirmar cuál es: decirle a un paciente que revise su rol lo manda a reclamar algo
          que está bien. */}
      {reason === 'sin_acceso' && (
        <Alert variant="warning" title="Tu cuenta no tiene secciones habilitadas">
          Iniciaste sesión correctamente, pero tu cuenta no tiene ninguna sección de la estación
          clínica habilitada. Si trabajas en la clínica, pide a la administración que revise tu rol.
          Si tu cuenta es de paciente, la estación todavía no tiene un portal para pacientes.
        </Alert>
      )}

      {/* Refresco de arranque sin sesión (S2): la sesión anterior se cerró y no se pudo recuperar.
          El aviso cubre las dos causas y no afirma ninguna: el emisor responde lo mismo cuando la
          cookie venció y cuando la cuenta fue dada de baja, así que decir «tu cuenta fue dada de
          baja» le mentiría a quien solo abrió la aplicación al día siguiente. Nombra la salida de
          la segunda —reactivar— porque es la que la persona no puede resolver sola. */}
      {reason === 'sesion_cerrada' && (
        <Alert variant="warning" title="Tu sesión anterior se cerró">
          La sesión anterior se cerró y no se pudo recuperar. Puede haber pasado el tiempo de
          inactividad o la cuenta puede haber sido dada de baja; si un administrador dio de baja tu
          cuenta, tiene que reactivarla antes de que puedas volver a entrar.
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
    </div>
  );
}