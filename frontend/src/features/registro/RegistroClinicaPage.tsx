import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { registroClinicaSchema, type RegistroClinicaValues } from './registro.schema';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
import { Button, Input, Select, Alert, Card, CardContent } from '@/components/ui';
import { Stethoscope, Building2, Users, UserCog, CheckCircle2 } from 'lucide-react';

/** Mensaje único del estado de error: el detalle técnico solo va a la consola (ADR-011). */
const MENSAJE_ERROR =
  'No fue posible registrar la clínica. Revisa la conexión con el servicio e inténtalo de nuevo.';

export function RegistroClinicaPage() {
  const navigate = useNavigate();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [resultado, setResultado] = useState<{ nombreClinica: string } | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegistroClinicaValues>({
    resolver: zodResolver(registroClinicaSchema),
    defaultValues: { plan: 'SOLO', maxMedicos: '1', maxConsultorios: '1' },
  });

  const onSubmit = async (values: RegistroClinicaValues) => {
    try {
      setIsSubmitting(true);
      setErrorMessage(null);
      const res = await api.post<{ organizacionId: string; nombreClinica: string }>(
        '/onboarding/registro-clinica',
        {
          ...values,
          maxMedicos: Number(values.maxMedicos),
          maxConsultorios: Number(values.maxConsultorios),
        },
      );
      setResultado({ nombreClinica: res.data.nombreClinica });
    } catch (err) {
      console.error('Error al registrar clínica:', resumenDeErrorHttp(err));
      setErrorMessage(MENSAJE_ERROR);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F8FAFC] flex flex-col items-center py-12 px-4 sm:px-6">
      <Link to="/" className="flex items-center gap-2.5 mb-8">
        <div className="w-9 h-9 rounded-xl bg-[#0A4D68] text-white flex items-center justify-center shadow-xs">
          <Stethoscope className="w-5 h-5 text-[#88BDDC]" />
        </div>
        <span className="font-heading font-bold text-lg tracking-tight text-[#0A4D68]">
          Vitalix
        </span>
      </Link>

      <div className="w-full max-w-2xl">
        {resultado ? (
          <Card>
            <CardContent className="p-8 text-center">
              <div className="w-12 h-12 rounded-2xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-6 h-6" />
              </div>
              <h2 className="font-heading font-bold text-xl text-[#0B1C30]">
                "{resultado.nombreClinica}" quedó registrada
              </h2>
              <p className="mt-2 text-sm text-[#64748B] max-w-md mx-auto">
                Este registro es una simulación: el alta real de clínicas todavía no tiene
                servicio de autenticación propio (está planeado para una etapa posterior del
                proyecto), así que por ahora no se crea una cuenta con la que puedas iniciar
                sesión.
              </p>
              <Button
                variant="primary"
                className="mt-6"
                onClick={() => navigate('/login')}
              >
                Ir a iniciar sesión
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            <div className="text-center mb-6">
              <h1 className="font-heading font-bold text-2xl text-[#0B1C30]">
                Registra tu clínica
              </h1>
              <p className="mt-1 text-sm text-[#64748B]">
                Configura los datos básicos de tu organización y de tu cuenta de administrador.
              </p>
            </div>

            {errorMessage && (
              <Alert variant="error" title="Error de registro" className="mb-4">
                {errorMessage}
              </Alert>
            )}

            <Card>
              <CardContent className="p-6 sm:p-8">
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                  <div>
                    <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-3">
                      <Building2 className="w-3.5 h-3.5" /> Datos de la clínica
                    </p>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <Input
                        label="Nombre de la clínica"
                        placeholder="Clínica San Rafael"
                        error={errors.nombreClinica?.message}
                        className="sm:col-span-2"
                        {...register('nombreClinica')}
                        required
                      />
                      <Select
                        label="Plan"
                        error={errors.plan?.message}
                        // "Enterprise" no se ofrece aquí a propósito: ese plan implica
                        // multi-sede, y el anteproyecto excluye explícitamente la gestión de
                        // varias clínicas/sucursales del alcance actual. Autorregistrarse como
                        // Enterprise prometería una función que el producto no tiene todavía;
                        // ese plan debe salir de un flujo de ventas asistido, no de este form.
                        options={[
                          { value: 'SOLO', label: 'Solo (un profesional)' },
                          { value: 'CLINICA', label: 'Clínica (varios médicos)' },
                        ]}
                        {...register('plan')}
                      />
                    </div>
                  </div>

                  <div className="pt-2 border-t border-[#F1F5F9]">
                    <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-3 mt-4">
                      <Users className="w-3.5 h-3.5" /> Límites de la organización
                    </p>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <Input
                        label="Máximo de médicos"
                        type="number"
                        min={1}
                        max={100}
                        error={errors.maxMedicos?.message}
                        {...register('maxMedicos')}
                        required
                      />
                      <Input
                        label="Máximo de consultorios"
                        type="number"
                        min={1}
                        max={50}
                        error={errors.maxConsultorios?.message}
                        {...register('maxConsultorios')}
                        required
                      />
                    </div>
                  </div>

                  <div className="pt-2 border-t border-[#F1F5F9]">
                    <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-3 mt-4">
                      <UserCog className="w-3.5 h-3.5" /> Cuenta de administrador
                    </p>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <Input
                        label="Nombre completo"
                        error={errors.nombreAdmin?.message}
                        className="sm:col-span-2"
                        {...register('nombreAdmin')}
                        required
                      />
                      <Input
                        label="Correo electrónico"
                        type="email"
                        error={errors.correoAdmin?.message}
                        className="sm:col-span-2"
                        {...register('correoAdmin')}
                        required
                      />
                      <Input
                        label="Contraseña"
                        type="password"
                        error={errors.password?.message}
                        {...register('password')}
                        required
                      />
                      <Input
                        label="Confirmar contraseña"
                        type="password"
                        error={errors.confirmarPassword?.message}
                        {...register('confirmarPassword')}
                        required
                      />
                    </div>
                  </div>

                  <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
                    <Button type="button" variant="outline" onClick={() => navigate('/')}>
                      Cancelar
                    </Button>
                    <Button type="submit" variant="primary" isLoading={isSubmitting}>
                      Registrar mi clínica
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>

            <p className="text-center text-xs text-[#94A3B8] mt-4">
              ¿Ya tienes una cuenta?{' '}
              <Link to="/login" className="font-medium text-[#0A4D68] hover:text-[#00354a]">
                Inicia sesión
              </Link>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
