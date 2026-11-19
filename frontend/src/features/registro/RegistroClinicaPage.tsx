import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import {
  cuerpoDeRegistro,
  registroClinicaSchema,
  type PlanDisponible,
  type RegistroClinicaValues,
} from './registro.schema';
import { api } from '@/lib/axios';
import { mensajeDelBackend, resumenDeErrorHttp } from '@/lib/apiError';
import { Button, Input, Select, Alert, Card, CardContent } from '@/components/ui';
import { Stethoscope, Building2, Users, UserCog, CheckCircle2 } from 'lucide-react';

/** Mensaje del registro fallido cuando el backend no adjunta uno propio. */
const MENSAJE_ERROR =
  'No fue posible registrar la clínica. Revisa los datos e inténtalo de nuevo.';

/** Mensaje cuando el catálogo de planes no se pudo cargar. */
const MENSAJE_PLANES =
  'No fue posible cargar los planes. Sin ellos no se pueden mostrar los límites de la clínica.';

/**
 * Registro público de una clínica desde la landing (S7).
 *
 * <p>Conecta el formulario con el contrato real: `POST /api/v1/onboarding/registro-clinica` crea la
 * organización y su primer administrador en una sola transacción, y `GET /api/v1/onboarding/planes`
 * publica las etiquetas y los límites de cada plan. Los límites <strong>no se eligen aquí</strong>:
 * se pintan en solo lectura según el plan seleccionado y no viajan en el cuerpo, porque los define
 * el plan y no quien se registra.</p>
 *
 * <p>La contraseña y su confirmación se piden y se validan entre sí en el cliente, pero
 * <strong>no se envían</strong>: la base no guarda contraseñas y el emisor de tokens usa una sola por
 * entorno. Después de registrar, la pantalla lo dice explícitamente junto al correo con el que hay
 * que entrar, porque quien escribió una contraseña va a intentar usarla.</p>
 */
export function RegistroClinicaPage() {
  const navigate = useNavigate();
  const [planes, setPlanes] = useState<PlanDisponible[]>([]);
  const [cargandoPlanes, setCargandoPlanes] = useState(true);
  const [errorPlanes, setErrorPlanes] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [resultado, setResultado] = useState<{
    nombreClinica: string;
    correoAdmin: string;
  } | null>(null);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<RegistroClinicaValues>({
    resolver: zodResolver(registroClinicaSchema),
    defaultValues: { plan: 'SOLO', password: '', confirmarPassword: '' },
  });

  // El catálogo se pide una vez al abrir la pantalla. Los `setState` viven en los callbacks de la
  // promesa y no en el cuerpo del efecto: marcar estado de forma síncrona dentro del efecto es el
  // patrón que el compilador de React rechaza, y aquí el estado inicial ya es «cargando».
  const aplicarPlanes = useCallback(
    (planesRecibidos: PlanDisponible[]) => {
      setPlanes(planesRecibidos);
      setErrorPlanes(null);
      const primero = planesRecibidos[0];
      if (primero) setValue('plan', primero.plan);
    },
    [setValue],
  );

  const mostrarErrorDePlanes = useCallback((err: unknown) => {
    console.error('Error al cargar los planes del onboarding:', resumenDeErrorHttp(err));
    setErrorPlanes(mensajeDelBackend(err, MENSAJE_PLANES));
  }, []);

  useEffect(() => {
    let vigente = true;
    api
      .get<PlanDisponible[]>('/onboarding/planes')
      .then((respuesta) => {
        if (vigente) aplicarPlanes(respuesta.data);
      })
      .catch((err: unknown) => {
        if (vigente) mostrarErrorDePlanes(err);
      })
      .finally(() => {
        if (vigente) setCargandoPlanes(false);
      });
    return () => {
      vigente = false;
    };
  }, [aplicarPlanes, mostrarErrorDePlanes]);

  /** Reintento manual del catálogo: vuelve a marcar «cargando» y repite la petición. */
  const reintentarPlanes = () => {
    setCargandoPlanes(true);
    setErrorPlanes(null);
    api
      .get<PlanDisponible[]>('/onboarding/planes')
      .then((respuesta) => aplicarPlanes(respuesta.data))
      .catch((err: unknown) => mostrarErrorDePlanes(err))
      .finally(() => setCargandoPlanes(false));
  };

  // `useWatch` y no `watch()`: la función que devuelve `useForm().watch` no se puede memoizar de
  // forma segura y el compilador de React omite este componente entero (aviso de eslint). El hook
  // da el mismo valor y sí es compatible.
  const planElegido = useWatch({ control, name: 'plan' });
  const planActual = useMemo(
    () => planes.find((plan) => plan.plan === planElegido),
    [planes, planElegido],
  );

  const onSubmit = async (values: RegistroClinicaValues) => {
    try {
      setIsSubmitting(true);
      setErrorMessage(null);
      const respuesta = await api.post<{ organizacionId: string; nombreClinica: string }>(
        '/onboarding/registro-clinica',
        cuerpoDeRegistro(values),
      );
      setResultado({
        nombreClinica: respuesta.data.nombreClinica,
        correoAdmin: values.correoAdmin.trim(),
      });
    } catch (err) {
      console.error('Error al registrar la clínica:', resumenDeErrorHttp(err));
      setErrorMessage(mensajeDelBackend(err, MENSAJE_ERROR));
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
            <CardContent className="p-8">
              <div className="w-12 h-12 rounded-2xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-6 h-6" />
              </div>
              <h2 className="font-heading font-bold text-xl text-[#0B1C30] text-center">
                «{resultado.nombreClinica}» quedó registrada
              </h2>
              <div className="mt-5 rounded-xl border border-[#E2E8F0] bg-[#F8FAFC] p-4 text-sm text-[#41484D]">
                <p className="font-semibold text-[#0B1C30] mb-1">Con qué entrar</p>
                <p>
                  Tu cuenta administradora es{' '}
                  <span className="font-semibold text-[#0B1C30]">{resultado.correoAdmin}</span>. Usa ese
                  correo y la contraseña del entorno del servicio de identidad (
                  <code className="text-xs">DEV_AUTH_PASSWORD</code> en desarrollo).
                </p>
                <p className="mt-2 text-xs text-[#64748B]">
                  La contraseña que escribiste en el formulario no se guarda: la base no almacena
                  contraseñas y todas las cuentas de este entorno se validan con esa contraseña del
                  servicio de identidad.
                </p>
              </div>
              <div className="mt-6 text-center">
                <Button variant="primary" onClick={() => navigate('/login')}>
                  Ir a iniciar sesión
                </Button>
              </div>
            </CardContent>
          </Card>
        ) : (
          <>
            <div className="text-center mb-6">
              <h1 className="font-heading font-bold text-2xl text-[#0B1C30]">
                Registra tu clínica
              </h1>
              <p className="mt-1 text-sm text-[#64748B]">
                Configura los datos básicos de tu organización y de tu cuenta de administración.
              </p>
            </div>

            {errorMessage && (
              <Alert variant="error" title="Error de registro" className="mb-4">
                {errorMessage}
              </Alert>
            )}

            {errorPlanes && (
              <Alert variant="error" title="No se pudieron cargar los planes" className="mb-4">
                <p>{errorPlanes}</p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="mt-3"
                  onClick={reintentarPlanes}
                >
                  Reintentar
                </Button>
              </Alert>
            )}

            <Card>
              <CardContent className="p-6 sm:p-8">
                {/* `noValidate`: la validación y sus mensajes son los del esquema, en español y
                    junto a cada campo. Con la validación del navegador activa, un correo con forma
                    inválida bloqueaba el envío antes de que la pantalla mostrara su propio mensaje
                    y el aviso salía en el idioma y el formato del navegador. */}
                <form onSubmit={handleSubmit(onSubmit)} className="space-y-6" noValidate>
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
                        options={planes.map((plan) => ({
                          value: plan.plan,
                          label: plan.etiqueta,
                        }))}
                        {...register('plan')}
                        disabled={cargandoPlanes || errorPlanes !== null}
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
                        value={textoDeLimite(planActual?.maxMedicos)}
                        readOnly
                        aria-readonly="true"
                        helperText="Lo define el plan."
                      />
                      <Input
                        label="Máximo de consultorios"
                        value={textoDeLimite(planActual?.maxConsultorios)}
                        readOnly
                        aria-readonly="true"
                        helperText="Lo define el plan."
                      />
                    </div>
                  </div>

                  <div className="pt-2 border-t border-[#F1F5F9]">
                    <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-3 mt-4">
                      <UserCog className="w-3.5 h-3.5" /> Cuenta de administración
                    </p>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <Input
                        label="Nombre del administrador"
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
                        helperText="Con este correo entrarás a tu clínica."
                        {...register('correoAdmin')}
                        required
                      />
                      <Input
                        label="Contraseña"
                        type="password"
                        error={errors.password?.message}
                        helperText="No se guarda: la cuenta entra con la contraseña del entorno."
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
                    <Button
                      type="submit"
                      variant="primary"
                      isLoading={isSubmitting}
                      disabled={cargandoPlanes || errorPlanes !== null}
                    >
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

/**
 * Texto de un límite del plan para la vista de solo lectura.
 *
 * @param limite límite publicado por el plan, o `undefined` mientras no hay plan cargado
 * @returns el número, o «Sin límite» cuando el plan no tiene tope (null)
 */
function textoDeLimite(limite: number | null | undefined): string {
  if (limite === null) return 'Sin límite';
  if (limite === undefined) return '—';
  return String(limite);
}
