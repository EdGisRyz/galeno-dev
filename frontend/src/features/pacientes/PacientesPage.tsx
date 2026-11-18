import { useState, useEffect, useMemo } from 'react';
import { differenceInYears } from 'date-fns';
import { api } from '@/lib/axios';
import { mensajeDelBackend, resumenDeErrorHttp } from '@/lib/apiError';
import {
  Button,
  Input,
  Select,
  Badge,
  Modal,
  Card,
  CardContent,
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
  Alert,
} from '@/components/ui';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import {
  SUGERENCIAS_DE_PARENTESCO,
  erroresDePaciente,
  type CampoDePaciente,
  type ErroresDePaciente,
} from '@/features/pacientes/validacionPaciente';
import { useAuthStore } from '@/store/auth.store';
import {
  Users,
  UserPlus,
  Search,
  Phone,
  Mail,
  IdCard,
  Home,
  UserCheck,
  UserX,
  Pencil,
  Baby,
  FileText,
  Loader2,
  RefreshCw,
} from 'lucide-react';

/** Mensaje único del estado de error de carga: el detalle técnico solo va a la consola. */
const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de pacientes. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Fallo propio de la reactivación: el mensaje de carga habla de leer el directorio y confundiría
 * al usuario sobre qué acción no se completó.
 */
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar al paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_REACTIVAR =
  'No fue posible reactivar al paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Respaldo del alta y de la edición. El rechazo por CURP duplicada (409) trae su propio motivo y
 * se muestra tal cual (S7); este texto solo cubre un fallo que no vino con mensaje.
 */
const MENSAJE_ERROR_GUARDAR =
  'No fue posible guardar la ficha del paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';

const EMPTY_FORM = {
  nombre: '',
  telefono: '',
  fechaNacimiento: '',
  sexo: '' as 'M' | 'F' | 'O' | '',
  email: '',
  notas: '',
  curp: '',
  ocupacion: '',
  domicilio: '',
  nombreResponsable: '',
  telefonoResponsable: '',
  parentesco: '',
};

function calcularEdad(fechaNacimiento?: string | null): string {
  if (!fechaNacimiento) return 'Sin registrar';
  const years = differenceInYears(new Date(), new Date(fechaNacimiento));
  return years < 1 ? 'Menor de 1 año' : `${years} años`;
}

export function PacientesPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la autoridad
   * del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2, guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');

  /**
   * La colección cuelga de la organización dueña: `GET/POST /organizaciones/{id}/pacientes`. El
   * ítem del paciente es plano (`PUT /pacientes/{id}`), así que solo la colección necesita la
   * organización. Sin claim no hay URL que pedir.
   */
  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/pacientes` : null;

  const [patients, setPatients] = useState<Paciente[]>([]);
  // Sin organización en la sesión la pantalla nace en el estado de error visible: no hay petición
  // que hacer ni se muestra un directorio vacío que afirmaría «no hay pacientes».
  const [isLoading, setIsLoading] = useState(() => rutaColeccion !== null);
  const [loadError, setLoadError] = useState<string | null>(() =>
    rutaColeccion ? null : MENSAJE_ERROR_CARGA,
  );
  const [search, setSearch] = useState('');

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [erroresForm, setErroresForm] = useState<ErroresDePaciente>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [bajaError, setBajaError] = useState<string | null>(null);
  // Motivo visible del último rechazo del alta o de la edición: el 409 por CURP duplicada trae el
  // suyo y se muestra tal cual, sin cambiarlo por un texto genérico.
  const [saveError, setSaveError] = useState<string | null>(null);

  const loadData = (ruta: string) => {
    // Se piden también los inactivos: la baja es lógica y el historial se conserva, así que ocultarlos
    // dejaría al usuario sin otra salida que volver a dar de alta al paciente, que es cómo se
    // duplicaba. El servicio los devuelve solo si se lo piden; sin el parámetro, el contrato no cambia.
    return api
      .get<Paciente[]>(`${ruta}?incluirInactivos=true`)
      .then((res) => {
        setPatients(res.data);
        setLoadError(null);
      })
      .catch((err) => {
        // Se registra un resumen sin secretos (API_ADR-011) y además se hace visible el fallo:
        // un directorio vacío por error no puede confundirse con «no hay pacientes».
        console.error('Error al cargar pacientes:', resumenDeErrorHttp(err));
        setPatients([]);
        setLoadError(MENSAJE_ERROR_CARGA);
      })
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    // Solo se pide lo que existe: sin organización no hay colección y el estado inicial ya lo dice.
    if (rutaColeccion) loadData(rutaColeccion);
  }, [rutaColeccion]);

  // Refresco manual: el loader y la limpieza del error se marcan aquí y no dentro de `loadData`,
  // para que el efecto inicial no encienda estados antes de que responda la petición.
  const handleRetry = () => {
    if (!rutaColeccion) return;
    setIsLoading(true);
    setLoadError(null);
    loadData(rutaColeccion);
  };

  const openCreateModal = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setErroresForm({});
    setSaveError(null);
    setIsModalOpen(true);
  };

  /**
   * La ficha se lee de los campos planos del paciente (S6): es la misma forma en la que el backend
   * la devuelve, así que no hay nada que desenvolver ni campos que se pierdan al editar.
   */
  const openEditModal = (patient: Paciente) => {
    setEditingId(patient.id);
    setSaveError(null);
    setErroresForm({});
    setForm({
      nombre: patient.nombre,
      telefono: patient.telefono || '',
      fechaNacimiento: patient.fechaNacimiento || '',
      sexo: patient.sexo || '',
      email: patient.email || '',
      notas: patient.notas || '',
      curp: patient.curp || '',
      ocupacion: patient.ocupacion || '',
      domicilio: patient.domicilio || '',
      nombreResponsable: patient.nombreResponsable || '',
      telefonoResponsable: patient.telefonoResponsable || '',
      parentesco: patient.parentesco || '',
    });
    setIsModalOpen(true);
  };

  /**
   * Escribe un campo con regla de formato y retira su error: el mensaje describía el valor anterior
   * y dejarlo visible mientras se corrige el dato es una advertencia falsa.
   */
  const actualizarCampo = (campo: CampoDePaciente, valor: string) => {
    setForm((f) => ({ ...f, [campo]: valor }));
    setErroresForm((prev) => {
      if (!prev[campo]) return prev;
      const siguiente = { ...prev };
      delete siguiente[campo];
      return siguiente;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // S9: las mismas reglas del backend, aplicadas antes de enviar. Un dato mal formado no sale de
    // la pantalla y el mensaje queda junto al campo, sin esperar la respuesta del servicio.
    const errores = erroresDePaciente(form);
    setErroresForm(errores);
    if (Object.keys(errores).length > 0) return;

    // S6/S8: los seis campos de la ficha van planos, al mismo nivel que el resto y en camelCase.
    // El objeto anidado `ficha` de antes no lo declaraba el backend y Spring lo descartaba en
    // silencio: la recepcionista escribía los datos y la tabla quedaba vacía sin ningún error.
    const payload = {
      nombre: form.nombre,
      telefono: form.telefono || null,
      fechaNacimiento: form.fechaNacimiento || null,
      sexo: form.sexo || null,
      email: form.email || null,
      notas: form.notas || null,
      curp: form.curp || null,
      ocupacion: form.ocupacion || null,
      domicilio: form.domicilio || null,
      nombreResponsable: form.nombreResponsable || null,
      telefonoResponsable: form.telefonoResponsable || null,
      parentesco: form.parentesco || null,
    };

    setSaveError(null);
    try {
      setIsSubmitting(true);
      if (editingId) {
        // El ítem es plano: la organización dueña no se envía ni se puede cambiar.
        const res = await api.put<Paciente>(`/pacientes/${editingId}`, payload);
        setPatients((prev) => prev.map((p) => (p.id === editingId ? res.data : p)));
        setAlertNotice('Ficha del paciente actualizada correctamente.');
      } else {
        if (!rutaColeccion) {
          // Sin organización de la sesión no hay colección donde registrar y no se inventa una.
          return;
        }
        const res = await api.post<Paciente>(rutaColeccion, payload);
        setPatients((prev) => [res.data, ...prev]);
        setAlertNotice('Paciente registrado correctamente.');
      }
      setIsModalOpen(false);
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      // El rechazo por CURP duplicada llega como 409 con el motivo en `mensaje`: se muestra tal
      // cual. El modal se queda abierto y la fila no se toca, para que el usuario corrija la CURP
      // sin volver a escribir la ficha.
      console.error('Error al guardar paciente:', resumenDeErrorHttp(err));
      setSaveError(mensajeDelBackend(err, MENSAJE_ERROR_GUARDAR));
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Reactivación de una baja lógica (S5): `POST /pacientes/{id}/activar` responde 204 sin cuerpo,
   * así que la fila se marca activa en el estado local y no se vuelve a pedir el directorio.
   * El listado incluye a los inactivos gracias al parámetro `incluirInactivos`, así que la fila
   * sigue visible después de recargar y la reactivación no obliga a un alta nueva.
   */
  const handleReactivar = async (paciente: Paciente) => {
    setReactivarError(null);
    try {
      await api.post(`/pacientes/${paciente.id}/activar`);
      setPatients((prev) =>
        prev.map((p) => (p.id === paciente.id ? { ...p, activo: true } : p)),
      );
      setAlertNotice('Paciente reactivado correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al reactivar paciente:', resumenDeErrorHttp(err));
      setReactivarError(MENSAJE_ERROR_REACTIVAR);
    }
  };

  /**
   * Baja lógica del paciente: `DELETE /pacientes/{id}` responde 204 sin cuerpo y el servicio solo
   * apaga el registro, así que la fila se marca inactiva en el estado local y aparece la acción
   * para reactivarla. No se pide confirmación: la baja es reversible en un clic y el historial se
   * conserva, que es justamente el motivo de que sea lógica.
   */
  const handleDesactivar = async (paciente: Paciente) => {
    setBajaError(null);
    try {
      await api.delete(`/pacientes/${paciente.id}`);
      setPatients((prev) =>
        prev.map((p) => (p.id === paciente.id ? { ...p, activo: false } : p)),
      );
      setAlertNotice('Paciente desactivado correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al desactivar paciente:', resumenDeErrorHttp(err));
      setBajaError(MENSAJE_ERROR_BAJA);
    }
  };

  /**
   * Búsqueda del directorio: nombre, teléfono, CURP y ocupación. La CURP se compara en minúsculas
   * por los dos lados, así que da igual escribir la forma canónica o como la dictaron al
   * mostrador; el teléfono se compara tal cual porque solo tiene dígitos y guiones.
   */
  const filteredPatients = useMemo(() => {
    const term = search.toLowerCase();
    return patients.filter(
      (p) =>
        !term ||
        p.nombre.toLowerCase().includes(term) ||
        (p.telefono || '').includes(term) ||
        (p.curp || '').toLowerCase().includes(term) ||
        (p.ocupacion || '').toLowerCase().includes(term)
    );
  }, [patients, search]);

  const stats = useMemo(() => {
    const total = patients.length;
    const activos = patients.filter((p) => p.activo).length;
    const pediatricos = patients.filter(
      (p) => p.fechaNacimiento && differenceInYears(new Date(), new Date(p.fechaNacimiento)) < 18
    ).length;
    const conNotas = patients.filter((p) => p.notas).length;
    return { total, activos, pediatricos, conNotas };
  }, [patients]);

  /**
   * Un dato que no se pudo leer no es cero: mientras carga o si la carga falló, la métrica se
   * muestra como desconocida. Escribir 0 afirmaría que el directorio está vacío.
   */
  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  return (
    <div className="space-y-6">
      {/* ─────────────────────────────────────────
          CABECERA DE PÁGINA
          ───────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
            Pacientes
          </h1>
          <p className="text-xs text-[#64748B] mt-1">
            Directorio de pacientes y ficha de identificación conforme a la NOM-004.
          </p>
        </div>

        <Button
          variant="primary"
          size="sm"
          onClick={openCreateModal}
          leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
        >
          Nuevo Paciente
        </Button>
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}

      {reactivarError && (
        <Alert variant="error" title="No se pudo reactivar al paciente">
          {reactivarError}
        </Alert>
      )}

      {bajaError && (
        <Alert variant="error" title="No se pudo desactivar al paciente">
          {bajaError}
        </Alert>
      )}

      {/* ─────────────────────────────────────────
          MÉTRICAS DEL DIRECTORIO
          ───────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Pacientes
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <Users className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Activos
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.activos)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center">
              <UserCheck className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#4338CA]">
                Pediátricos
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#4338CA] mt-1 tnum">
                {metrica(stats.pediatricos)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EEF2FF] text-[#4F46E5] flex items-center justify-center">
              <Baby className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#B45309]">
                Con Notas Clínicas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#B45309] mt-1 tnum">
                {metrica(stats.conNotas)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#FFFBEB] text-[#D97706] flex items-center justify-center">
              <FileText className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ─────────────────────────────────────────
          BÚSQUEDA
          ───────────────────────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre, teléfono, CURP u ocupación..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            leftIcon={<Search className="w-4 h-4" />}
          />
        </CardContent>
      </Card>

      {/* ─────────────────────────────────────────
          TABLA DE PACIENTES
          ───────────────────────────────────────── */}
      <div className="space-y-3">
        {!isLoading && !loadError && (
          <div className="flex items-center justify-between text-xs text-[#64748B] px-1">
            <span>
              Mostrando <strong>{filteredPatients.length}</strong> pacientes registrados
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Paciente</TableHead>
              <TableHead>Edad / Nacimiento</TableHead>
              <TableHead>Contacto</TableHead>
              <TableHead>CURP</TableHead>
              <TableHead>Responsable</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={7} className="text-center py-12">
                  <span
                    role="status"
                    aria-live="polite"
                    className="inline-flex items-center gap-2 text-[#64748B]"
                  >
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Cargando pacientes...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={7} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar los pacientes">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                      <span>{loadError}</span>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={handleRetry}
                        leftIcon={<RefreshCw className="w-3.5 h-3.5" />}
                      >
                        Reintentar
                      </Button>
                    </div>
                  </Alert>
                </TableCell>
              </TableRow>
            ) : filteredPatients.length > 0 ? (
              filteredPatients.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>
                    <span className="font-semibold text-sm text-[#0B1C30] block">{p.nombre}</span>
                    {p.sexo && (
                      <span className="bg-[#EFF4FF] text-[#0A4D68] px-1.5 py-0.2 rounded text-[10px] font-bold mt-0.5 inline-block">
                        {p.sexo}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] block">{calcularEdad(p.fechaNacimiento)}</span>
                    <span className="text-[11px] text-[#94A3B8] block mt-0.5">
                      {p.fechaNacimiento || 'Sin fecha'}
                    </span>
                  </TableCell>

                  <TableCell>
                    {p.telefono && (
                      <span className="text-xs text-[#41484D] flex items-center gap-1.5">
                        <Phone className="w-3 h-3 text-[#64748B]" /> {p.telefono}
                      </span>
                    )}
                    {p.email && (
                      <span className="text-[11px] text-[#64748B] flex items-center gap-1.5 mt-0.5">
                        <Mail className="w-3 h-3" /> {p.email}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] font-mono">
                      {p.curp || '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] block">
                      {p.nombreResponsable || '—'}
                    </span>
                    {/* El parentesco se muestra junto al responsable: «quién es» y «qué es del
                        paciente» son el mismo dato para quien atiende el mostrador. */}
                    {p.parentesco && (
                      <span className="text-[11px] text-[#64748B] block mt-0.5">
                        {p.parentesco}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <Badge variant={p.activo ? 'success' : 'default'} showDot>
                      {p.activo ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>

                  <TableCell className="text-right">
                    <div className="inline-flex items-center justify-end gap-2">
                      {/* La baja es lógica, así que una fila inactiva se puede reactivar sin
                          duplicar al paciente. Mismo tamaño y forma que el resto de acciones de
                          la tabla, con el color positivo de los estados activos. */}
                      {!p.activo && (
                        <button
                          type="button"
                          onClick={() => handleReactivar(p)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#059669] text-white hover:bg-[#047857] text-xs font-medium transition-all shadow-2xs"
                          title="Reactivar al paciente dado de baja"
                        >
                          <UserCheck className="w-3.5 h-3.5 text-[#D1FAE5]" />
                          <span className="hidden sm:inline">Reactivar</span>
                        </button>
                      )}
                      {p.activo && (
                        <button
                          type="button"
                          onClick={() => handleDesactivar(p)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                          title="Dar de baja al paciente"
                        >
                          <UserX className="w-3.5 h-3.5" />
                          <span className="hidden sm:inline">Desactivar</span>
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => openEditModal(p)}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                        title="Editar ficha del paciente"
                      >
                        <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                        <span className="hidden sm:inline">Ficha</span>
                      </button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} className="text-center py-12">
                  {search ? (
                    <span className="text-[#94A3B8]">
                      No se encontraron pacientes con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <Users className="w-4 h-4 text-[#64748B]" />
                        Aún no hay pacientes registrados
                      </p>
                      <p className="text-[#94A3B8]">
                        Da de alta al primer paciente con el botón «Nuevo Paciente».
                      </p>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* ─────────────────────────────────────────
          MODAL DE PACIENTE (CREAR / EDITAR FICHA NOM-004)
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingId ? 'Editar Ficha del Paciente' : 'Registrar Nuevo Paciente'}
        description="Datos básicos y ficha de identificación según NOM-004."
        maxWidth="lg"
      >
        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
              Datos Básicos
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Nombre Completo"
                value={form.nombre}
                onChange={(e) => actualizarCampo('nombre', e.target.value)}
                error={erroresForm.nombre}
                required
              />
              <Select
                label="Sexo"
                value={form.sexo}
                onChange={(e) => setForm((f) => ({ ...f, sexo: e.target.value as 'M' | 'F' | 'O' }))}
                options={[
                  { value: 'M', label: 'Masculino' },
                  { value: 'F', label: 'Femenino' },
                  { value: 'O', label: 'Otro' },
                ]}
                placeholder="Selecciona..."
              />
              <Input
                label="Teléfono"
                value={form.telefono}
                onChange={(e) => actualizarCampo('telefono', e.target.value)}
                error={erroresForm.telefono}
                leftIcon={<Phone className="w-4 h-4" />}
              />
              <Input
                label="Fecha de Nacimiento"
                type="date"
                value={form.fechaNacimiento}
                onChange={(e) => setForm((f) => ({ ...f, fechaNacimiento: e.target.value }))}
              />
              <Input
                label="Correo Electrónico"
                type="email"
                value={form.email}
                onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                leftIcon={<Mail className="w-4 h-4" />}
                className="sm:col-span-2"
              />
            </div>
            <div className="mt-4">
              <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
                Notas Clínicas Generales
              </label>
              <textarea
                rows={2}
                className="block w-full rounded-lg border border-[#CBD5E1] bg-white p-3 text-sm text-[#0B1C30] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15"
                placeholder="Ej. Alergias, padecimientos crónicos..."
                value={form.notas}
                onChange={(e) => setForm((f) => ({ ...f, notas: e.target.value }))}
              />
            </div>
          </div>

          <div className="pt-4 border-t border-[#F1F5F9]">
            <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
              Ficha de Identificación (NOM-004)
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="CURP"
                value={form.curp}
                onChange={(e) => setForm((f) => ({ ...f, curp: e.target.value.toUpperCase() }))}
                leftIcon={<IdCard className="w-4 h-4" />}
                maxLength={18}
              />
              <Input
                label="Ocupación"
                value={form.ocupacion}
                onChange={(e) => actualizarCampo('ocupacion', e.target.value)}
                error={erroresForm.ocupacion}
              />
              <Input
                label="Domicilio Completo"
                value={form.domicilio}
                onChange={(e) => actualizarCampo('domicilio', e.target.value)}
                error={erroresForm.domicilio}
                leftIcon={<Home className="w-4 h-4" />}
                className="sm:col-span-2"
              />
              <Input
                label="Nombre del Responsable"
                value={form.nombreResponsable}
                onChange={(e) => actualizarCampo('nombreResponsable', e.target.value)}
                error={erroresForm.nombreResponsable}
                helperText="Para menores de edad o urgencias"
              />
              {/* El parentesco es texto con sugerencias, no una lista cerrada (S6): «amigo»,
                  «vecino» o «institución» son casos reales y siguen siendo válidos. Las formas
                  «hermano/a» van en singular porque la barra no pertenece al conjunto de caracteres
                  que la regla acepta. */}
              <Input
                label="Parentesco"
                value={form.parentesco}
                onChange={(e) => actualizarCampo('parentesco', e.target.value)}
                error={erroresForm.parentesco}
                list="sugerencias-parentesco"
                helperText="Relación con el paciente: madre, tutor, hermano..."
              />
              <datalist id="sugerencias-parentesco">
                {SUGERENCIAS_DE_PARENTESCO.map((parentesco) => (
                  <option key={parentesco} value={parentesco} />
                ))}
              </datalist>
              <Input
                label="Teléfono del Responsable"
                value={form.telefonoResponsable}
                onChange={(e) => actualizarCampo('telefonoResponsable', e.target.value)}
                error={erroresForm.telefonoResponsable}
              />
            </div>
          </div>

          {saveError && (
            <Alert variant="error" title="No se pudo guardar la ficha del paciente">
              {saveError}
            </Alert>
          )}

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsModalOpen(false)}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
            >
              {editingId ? 'Guardar Cambios' : 'Registrar Paciente'}
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
