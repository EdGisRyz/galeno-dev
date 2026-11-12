import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
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
import type { Medico, HorarioMedico } from '@/features/medicos/medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';
import {
  Stethoscope,
  Plus,
  Search,
  Phone,
  IdCard,
  Pencil,
  Trash2,
  Loader2,
  RefreshCw,
  Clock,
  CalendarClock,
} from 'lucide-react';

const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de médicos. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar el médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar el médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_HORARIOS =
  'No fue posible obtener los horarios de este médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_HORARIO_BAJA =
  'No fue posible eliminar el bloque de disponibilidad. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_HORARIO_GUARDADO =
  'No fue posible guardar el bloque de disponibilidad. Revisa que la hora de inicio sea anterior a la de fin y que la duración sea válida.';

const DIAS_SEMANA = [
  { value: 0, label: 'Domingo' },
  { value: 1, label: 'Lunes' },
  { value: 2, label: 'Martes' },
  { value: 3, label: 'Miércoles' },
  { value: 4, label: 'Jueves' },
  { value: 5, label: 'Viernes' },
  { value: 6, label: 'Sábado' },
];

const DURACIONES = [20, 30, 45, 60];

const EMPTY_FORM = {
  nombre: '',
  especialidad: '',
  cedula: '',
  telefono: '',
  tarifaBase: '',
  consultorioId: '',
};

const EMPTY_HORARIO_FORM = {
  diaSemana: '1',
  horaInicio: '09:00',
  horaFin: '14:00',
  duracionConsulta: '30',
};

export function MedicosPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la
   * autoridad del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2,
   * guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  const rolDeSesion = useAuthStore((state) => state.user?.role);
  const medicoIdDeSesion = useAuthStore((state) => state.user?.medicoId ?? null);
  // Alta, edición y baja de médico son de ADMIN en el backend (MedicoController); MEDICO solo lee.
  // La UI oculta las acciones de escritura para no ofrecer un botón que el backend va a rechazar.
  const puedeEditarMedicos = rolDeSesion === 'ADMIN';

  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/medicos` : null;
  const rutaConsultorios = organizacionId ? `/organizaciones/${organizacionId}/consultorios` : null;

  const [medicos, setMedicos] = useState<Medico[]>([]);
  const [consultorios, setConsultorios] = useState<Consultorio[]>([]);
  const [isLoading, setIsLoading] = useState(() => rutaColeccion !== null);
  const [loadError, setLoadError] = useState<string | null>(() =>
    rutaColeccion ? null : MENSAJE_ERROR_CARGA,
  );
  const [search, setSearch] = useState('');

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);

  // Panel de horarios: por médico, abierto bajo demanda (no se precargan todas las agendas).
  const [horariosDeId, setHorariosDeId] = useState<string | null>(null);
  const [horarios, setHorarios] = useState<HorarioMedico[]>([]);
  const [isLoadingHorarios, setIsLoadingHorarios] = useState(false);
  const [horariosError, setHorariosError] = useState<string | null>(null);
  const [horarioForm, setHorarioForm] = useState(EMPTY_HORARIO_FORM);
  const [editingHorarioId, setEditingHorarioId] = useState<string | null>(null);
  const [isSavingHorario, setIsSavingHorario] = useState(false);
  const [horarioFormError, setHorarioFormError] = useState<string | null>(null);
  const [horarioAEliminar, setHorarioAEliminar] = useState<string | null>(null);

  // Los horarios sí son del médico: el backend permite a MEDICO crear, editar y borrar bloques de
  // **su propia** agenda, y responde 403 con la de otro. La UI replica esa regla con el claim
  // `medicoId` de la sesión en lugar de esconder el panel entero.
  const puedeGestionarHorarios =
    rolDeSesion === 'ADMIN' || (rolDeSesion === 'MEDICO' && medicoIdDeSesion === horariosDeId);

  // `useCallback` porque el efecto de carga depende de esta función: si se recreara en cada
  // render, el efecto se dispararía en bucle.
  const loadData = useCallback((ruta: string, rutaConsultorios: string | null) => {
    return Promise.all([
      api.get<Medico[]>(ruta),
      rutaConsultorios ? api.get<Consultorio[]>(rutaConsultorios) : null,
    ])
      .then(([medicosRes, consultoriosRes]) => {
        setMedicos(medicosRes.data);
        setConsultorios(consultoriosRes ? consultoriosRes.data : []);
        setLoadError(null);
      })
      .catch((err) => {
        console.error('Error al cargar médicos:', resumenDeErrorHttp(err));
        setMedicos([]);
        setLoadError(MENSAJE_ERROR_CARGA);
      })
      .finally(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    if (rutaColeccion) loadData(rutaColeccion, rutaConsultorios);
  }, [rutaColeccion, rutaConsultorios, loadData]);

  /**
   * Los avisos de éxito se ocultan solos. El temporizador se guarda en una referencia para poder
   * cancelarlo: uno pendiente intentaría escribir el estado de un componente ya desmontado.
   */
  const avisoRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const programarOcultarAviso = () => {
    if (avisoRef.current) clearTimeout(avisoRef.current);
    avisoRef.current = setTimeout(() => setAlertNotice(null), 3500);
  };
  useEffect(
    () => () => {
      if (avisoRef.current) clearTimeout(avisoRef.current);
    },
    [],
  );

  const handleRetry = () => {
    if (!rutaColeccion) return;
    setIsLoading(true);
    setLoadError(null);
    loadData(rutaColeccion, rutaConsultorios);
  };

  const consultorioPorId = (id: string) => consultorios.find((c) => c.id === id);

  const openCreateModal = () => {
    setEditingId(null);
    setForm({ ...EMPTY_FORM, consultorioId: consultorios[0]?.id || '' });
    setSaveError(null);
    setIsModalOpen(true);
  };

  const openEditModal = (medico: Medico) => {
    setEditingId(medico.id);
    setForm({
      nombre: medico.nombre,
      especialidad: medico.especialidad || '',
      cedula: medico.cedula || '',
      telefono: medico.telefono || '',
      tarifaBase: medico.tarifaBase != null ? String(medico.tarifaBase) : '',
      consultorioId: medico.consultorioId,
    });
    setSaveError(null);
    setIsModalOpen(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.nombre.trim() || !form.consultorioId) return;

    const payload = {
      consultorioId: form.consultorioId,
      nombre: form.nombre,
      especialidad: form.especialidad || null,
      cedula: form.cedula || null,
      telefono: form.telefono || null,
      tarifaBase: form.tarifaBase ? Number(form.tarifaBase) : null,
    };

    try {
      setIsSubmitting(true);
      setSaveError(null);
      if (editingId) {
        // El consultorio no se puede cambiar por esta vía: el backend responde 400 si difiere del
        // actual (R13). El formulario lo mantiene bloqueado al editar para no ofrecer ese cambio.
        const res = await api.put<Medico>(`/medicos/${editingId}`, payload);
        setMedicos((prev) => prev.map((m) => (m.id === editingId ? res.data : m)));
        setAlertNotice('Médico actualizado correctamente.');
      } else {
        if (!rutaColeccion) return;
        const res = await api.post<Medico>(rutaColeccion, payload);
        setMedicos((prev) => [res.data, ...prev]);
        setAlertNotice('Médico registrado correctamente.');
      }
      setIsModalOpen(false);
      programarOcultarAviso();
    } catch (err) {
      console.error('Error al guardar médico:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSubmitting(false);
    }
  };

  // No hay botón "Activar": el backend no expone reactivación (MedicoController solo tiene
  // crear/listar/buscar/actualizar/desactivar). Misma brecha señalada en Consultorios (HU3-5).
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    try {
      setIsDeactivating(true);
      await api.delete(`/medicos/${deactivatingId}`);
      setMedicos((prev) =>
        prev.map((m) => (m.id === deactivatingId ? { ...m, activo: false } : m)),
      );
      setAlertNotice('Médico desactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      console.error('Error al desactivar médico:', resumenDeErrorHttp(err));
      setAlertNotice(null);
      setSaveError(MENSAJE_ERROR_BAJA);
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

  const openHorarios = (medicoId: string) => {
    setHorariosDeId(medicoId);
    setHorarios([]);
    setHorariosError(null);
    setHorarioForm(EMPTY_HORARIO_FORM);
    setEditingHorarioId(null);
    setHorarioFormError(null);
    setIsLoadingHorarios(true);
    api
      .get<HorarioMedico[]>(`/medicos/${medicoId}/horarios`)
      .then((res) => setHorarios(res.data))
      .catch((err) => {
        console.error('Error al cargar horarios:', resumenDeErrorHttp(err));
        setHorariosError(MENSAJE_ERROR_HORARIOS);
      })
      .finally(() => setIsLoadingHorarios(false));
  };

  const openEditHorario = (h: HorarioMedico) => {
    setEditingHorarioId(h.id);
    setHorarioForm({
      diaSemana: String(h.diaSemana),
      horaInicio: h.horaInicio.slice(0, 5),
      horaFin: h.horaFin.slice(0, 5),
      duracionConsulta: String(h.duracionConsulta),
    });
    setHorarioFormError(null);
  };

  const handleHorarioSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!horariosDeId) return;

    const payload = {
      diaSemana: Number(horarioForm.diaSemana),
      horaInicio: `${horarioForm.horaInicio}:00`,
      horaFin: `${horarioForm.horaFin}:00`,
      duracionConsulta: Number(horarioForm.duracionConsulta),
    };

    try {
      setIsSavingHorario(true);
      setHorarioFormError(null);
      if (editingHorarioId) {
        const res = await api.put<HorarioMedico>(`/horarios/${editingHorarioId}`, payload);
        setHorarios((prev) => prev.map((h) => (h.id === editingHorarioId ? res.data : h)));
      } else {
        const res = await api.post<HorarioMedico>(`/medicos/${horariosDeId}/horarios`, payload);
        // El listado del backend viene ordenado por día y hora; agregar al final rompería ese
        // orden hasta recargar, así que se reinserta respetando el contrato.
        setHorarios((prev) =>
          [...prev, res.data].sort(
            (a, b) => a.diaSemana - b.diaSemana || a.horaInicio.localeCompare(b.horaInicio),
          ),
        );
      }
      setHorarioForm(EMPTY_HORARIO_FORM);
      setEditingHorarioId(null);
    } catch (err) {
      console.error('Error al guardar horario:', resumenDeErrorHttp(err));
      setHorarioFormError(MENSAJE_ERROR_HORARIO_GUARDADO);
    } finally {
      setIsSavingHorario(false);
    }
  };

  const handleDeleteHorario = async () => {
    if (!horarioAEliminar) return;
    try {
      await api.delete(`/horarios/${horarioAEliminar}`);
      // Borrado físico (no baja lógica): `horario_medico` no tiene columna `activo`.
      setHorarios((prev) => prev.filter((h) => h.id !== horarioAEliminar));
    } catch (err) {
      console.error('Error al eliminar horario:', resumenDeErrorHttp(err));
      setHorariosError(MENSAJE_ERROR_HORARIO_BAJA);
    } finally {
      setHorarioAEliminar(null);
    }
  };

  const filteredMedicos = useMemo(() => {
    const term = search.toLowerCase();
    return medicos.filter(
      (m) =>
        !term ||
        m.nombre.toLowerCase().includes(term) ||
        (m.especialidad || '').toLowerCase().includes(term) ||
        (m.cedula || '').toLowerCase().includes(term),
    );
  }, [medicos, search]);

  // El servicio solo devuelve médicos activos, así que un conteo de inactivos siempre daría 0:
  // la segunda métrica se deriva de lo que sí llegó.
  const stats = useMemo(() => {
    const total = medicos.length;
    const conCedula = medicos.filter((m) => (m.cedula || '').trim() !== '').length;
    return { total, conCedula };
  }, [medicos]);

  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  const medicoADesactivar = medicos.find((m) => m.id === deactivatingId);
  const medicoDeHorarios = medicos.find((m) => m.id === horariosDeId);
  const horarioAEliminarDatos = horarios.find((h) => h.id === horarioAEliminar);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
            Médicos
          </h1>
          <p className="text-xs text-[#64748B] mt-1">
            Directorio de médicos, su consultorio asignado y sus horarios de disponibilidad.
          </p>
        </div>

        {puedeEditarMedicos && (
          <Button
            variant="primary"
            size="sm"
            onClick={openCreateModal}
            disabled={consultorios.length === 0}
            leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
          >
            Nuevo Médico
          </Button>
        )}
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}
      {saveError && (
        <Alert variant="error" title="Error">
          {saveError}
        </Alert>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Médicos
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <Stethoscope className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Con cédula
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.conCedula)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center">
              <IdCard className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre, especialidad o cédula..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            leftIcon={<Search className="w-4 h-4" />}
          />
        </CardContent>
      </Card>

      <div className="space-y-3">
        {!isLoading && !loadError && (
          <div className="flex items-center justify-between text-xs text-[#64748B] px-1">
            <span>
              Mostrando <strong>{filteredMedicos.length}</strong> médicos registrados
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Médico</TableHead>
              <TableHead>Consultorio</TableHead>
              <TableHead>Contacto</TableHead>
              <TableHead>Tarifa base</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={6} className="text-center py-12">
                  <span
                    role="status"
                    aria-live="polite"
                    className="inline-flex items-center gap-2 text-[#64748B]"
                  >
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Cargando médicos...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={6} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar los médicos">
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
            ) : filteredMedicos.length > 0 ? (
              filteredMedicos.map((m) => (
                <TableRow key={m.id}>
                  <TableCell>
                    <span className="font-semibold text-sm text-[#0B1C30] block">{m.nombre}</span>
                    {m.especialidad && (
                      <span className="text-[11px] text-[#64748B] block mt-0.5">
                        {m.especialidad}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D]">
                      {consultorioPorId(m.consultorioId)?.nombre || '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    {m.telefono ? (
                      <span className="text-xs text-[#41484D] flex items-center gap-1.5">
                        <Phone className="w-3 h-3 text-[#64748B]" /> {m.telefono}
                      </span>
                    ) : (
                      <span className="text-xs text-[#94A3B8]">—</span>
                    )}
                    {m.cedula && (
                      <span className="text-[11px] text-[#64748B] flex items-center gap-1.5 mt-0.5">
                        <IdCard className="w-3 h-3" /> {m.cedula}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] tnum">
                      {m.tarifaBase != null ? `$${m.tarifaBase.toFixed(2)}` : '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    <Badge variant={m.activo ? 'success' : 'default'} showDot>
                      {m.activo ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>

                  <TableCell className="text-right">
                    <div className="inline-flex items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => openHorarios(m.id)}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#CBD5E1] text-[#0A4D68] hover:bg-[#EFF4FF] text-xs font-medium transition-all"
                        title="Ver horarios de disponibilidad"
                      >
                        <CalendarClock className="w-3.5 h-3.5" />
                        <span className="hidden sm:inline">Horarios</span>
                      </button>
                      {puedeEditarMedicos && (
                        <>
                          <button
                            type="button"
                            onClick={() => openEditModal(m)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                            title="Editar médico"
                          >
                            <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                            <span className="hidden sm:inline">Editar</span>
                          </button>
                          {m.activo && (
                            <button
                              type="button"
                              onClick={() => setDeactivatingId(m.id)}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                              title="Desactivar médico"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                              <span className="hidden sm:inline">Desactivar</span>
                            </button>
                          )}
                        </>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={6} className="text-center py-12">
                  {search ? (
                    <span className="text-[#94A3B8]">
                      No se encontraron médicos con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <Stethoscope className="w-4 h-4 text-[#64748B]" />
                        Aún no hay médicos registrados
                      </p>
                      {puedeEditarMedicos && (
                        <p className="text-[#94A3B8]">
                          {consultorios.length === 0
                            ? 'Da de alta un consultorio primero: un médico siempre necesita uno asignado.'
                            : 'Da de alta al primer médico con el botón «Nuevo Médico».'}
                        </p>
                      )}
                    </div>
                  )}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* ─────────────────────────────────────────
          MODAL DE MÉDICO (CREAR / EDITAR)
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingId ? 'Editar Médico' : 'Registrar Nuevo Médico'}
        description="Datos profesionales del médico y el consultorio donde atiende."
        maxWidth="md"
      >
        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Nombre"
            value={form.nombre}
            onChange={(e) => setForm((f) => ({ ...f, nombre: e.target.value }))}
            required
            maxLength={120}
          />
          <Select
            label="Consultorio"
            value={form.consultorioId}
            onChange={(e) => setForm((f) => ({ ...f, consultorioId: e.target.value }))}
            options={consultorios.map((c) => ({ value: c.id, label: c.nombre }))}
            placeholder="Selecciona..."
            required
            disabled={editingId !== null}
            helperText={
              editingId !== null
                ? 'El consultorio no se puede cambiar una vez creado el médico.'
                : undefined
            }
          />
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="Especialidad"
              value={form.especialidad}
              onChange={(e) => setForm((f) => ({ ...f, especialidad: e.target.value }))}
              maxLength={80}
            />
            <Input
              label="Cédula profesional"
              value={form.cedula}
              onChange={(e) => setForm((f) => ({ ...f, cedula: e.target.value }))}
              leftIcon={<IdCard className="w-4 h-4" />}
              maxLength={30}
            />
            <Input
              label="Teléfono"
              value={form.telefono}
              onChange={(e) => setForm((f) => ({ ...f, telefono: e.target.value }))}
              leftIcon={<Phone className="w-4 h-4" />}
              maxLength={20}
            />
            <Input
              label="Tarifa base"
              type="number"
              min="0"
              step="0.01"
              value={form.tarifaBase}
              onChange={(e) => setForm((f) => ({ ...f, tarifaBase: e.target.value }))}
            />
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsModalOpen(false)}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
            >
              {editingId ? 'Guardar Cambios' : 'Registrar Médico'}
            </Button>
          </div>
        </form>
      </Modal>

      {/* ─────────────────────────────────────────
          MODAL DE CONFIRMACIÓN DE BAJA
          ───────────────────────────────────────── */}
      <Modal
        isOpen={deactivatingId !== null}
        onClose={() => setDeactivatingId(null)}
        title="Desactivar médico"
        description="El médico deja de estar disponible para nuevas citas; su historial se conserva."
        maxWidth="sm"
      >
        <p className="text-sm text-[#41484D]">
          ¿Desactivar <strong>{medicoADesactivar?.nombre}</strong>? El sistema todavía no ofrece
          una forma de reactivarlo: confírmalo solo si estás seguro.
        </p>
        <div className="flex justify-end gap-2 pt-5">
          <Button type="button" variant="outline" onClick={() => setDeactivatingId(null)}>
            Cancelar
          </Button>
          <Button
            type="button"
            variant="danger"
            isLoading={isDeactivating}
            onClick={handleDeactivate}
            leftIcon={<Trash2 className="w-4 h-4" />}
          >
            Desactivar
          </Button>
        </div>
      </Modal>

      {/* ─────────────────────────────────────────
          MODAL DE CONFIRMACIÓN DE BORRADO DE BLOQUE
          ───────────────────────────────────────── */}
      {/* El borrado de un bloque es físico: `horario_medico` no tiene baja lógica, así que
          confirmarlo es la única barrera antes de una pérdida irreversible. */}
      <Modal
        isOpen={horarioAEliminar !== null}
        onClose={() => setHorarioAEliminar(null)}
        title="Eliminar bloque de disponibilidad"
        description="El bloque se elimina de forma definitiva: no se puede recuperar."
        maxWidth="sm"
      >
        <p className="text-sm text-[#41484D]">
          {horarioAEliminarDatos ? (
            <>
              ¿Eliminar el bloque del{' '}
              <strong>
                {DIAS_SEMANA.find((d) => d.value === horarioAEliminarDatos.diaSemana)?.label}
              </strong>{' '}
              de {horarioAEliminarDatos.horaInicio.slice(0, 5)} a{' '}
              {horarioAEliminarDatos.horaFin.slice(0, 5)}? No se puede deshacer.
            </>
          ) : (
            '¿Eliminar este bloque? No se puede deshacer.'
          )}
        </p>
        <div className="flex justify-end gap-2 pt-5">
          <Button type="button" variant="outline" onClick={() => setHorarioAEliminar(null)}>
            Cancelar
          </Button>
          <Button
            type="button"
            variant="danger"
            onClick={handleDeleteHorario}
            leftIcon={<Trash2 className="w-4 h-4" />}
          >
            Eliminar bloque
          </Button>
        </div>
      </Modal>

      {/* ─────────────────────────────────────────
          PANEL DE HORARIOS DE DISPONIBILIDAD
          ───────────────────────────────────────── */}
      <Modal
        isOpen={horariosDeId !== null}
        onClose={() => setHorariosDeId(null)}
        title={`Horarios de ${medicoDeHorarios?.nombre || ''}`}
        description="Bloques de disponibilidad semanal. Las citas se agendan dentro de estos horarios."
        maxWidth="lg"
      >
        <div className="space-y-4">
          {horariosError && (
            <Alert variant="error" title="Error">
              {horariosError}
            </Alert>
          )}

          {isLoadingHorarios ? (
            <span
              role="status"
              aria-live="polite"
              className="inline-flex items-center gap-2 text-[#64748B] text-sm py-4"
            >
              <Loader2 className="w-4 h-4 animate-spin" />
              Cargando horarios...
            </span>
          ) : horarios.length > 0 ? (
            <ul className="space-y-1.5">
              {horarios.map((h) => (
                <li
                  key={h.id}
                  className="flex items-center justify-between px-3 py-2 rounded-lg border border-[#E2E8F0] bg-[#F8FAFC]"
                >
                  <span className="text-xs text-[#41484D] flex items-center gap-2">
                    <Clock className="w-3.5 h-3.5 text-[#64748B]" />
                    <strong className="text-[#0B1C30]">
                      {DIAS_SEMANA.find((d) => d.value === h.diaSemana)?.label}
                    </strong>
                    {h.horaInicio.slice(0, 5)}–{h.horaFin.slice(0, 5)} · citas de{' '}
                    {h.duracionConsulta} min
                  </span>
                  {puedeGestionarHorarios && (
                    <div className="inline-flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => openEditHorario(h)}
                        className="p-1.5 rounded-md text-[#0A4D68] hover:bg-[#E5EEFF]"
                        title="Editar bloque"
                      >
                        <Pencil className="w-3.5 h-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setHorarioAEliminar(h.id)}
                        className="p-1.5 rounded-md text-[#E11D48] hover:bg-[#FEF2F2]"
                        title="Eliminar bloque"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-[#94A3B8] py-2">
              Este médico todavía no tiene bloques de disponibilidad registrados.
            </p>
          )}

          {puedeGestionarHorarios && (
            <form
              onSubmit={handleHorarioSubmit}
              className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-4 border-t border-[#F1F5F9] items-end"
            >
              {horarioFormError && (
                <div className="col-span-2 sm:col-span-4">
                  <Alert variant="error">{horarioFormError}</Alert>
                </div>
              )}
              <Select
                label="Día"
                value={horarioForm.diaSemana}
                onChange={(e) => setHorarioForm((f) => ({ ...f, diaSemana: e.target.value }))}
                options={DIAS_SEMANA.map((d) => ({ value: d.value, label: d.label }))}
              />
              <Input
                label="Hora inicio"
                type="time"
                value={horarioForm.horaInicio}
                onChange={(e) => setHorarioForm((f) => ({ ...f, horaInicio: e.target.value }))}
                required
              />
              <Input
                label="Hora fin"
                type="time"
                value={horarioForm.horaFin}
                onChange={(e) => setHorarioForm((f) => ({ ...f, horaFin: e.target.value }))}
                required
              />
              <Select
                label="Duración cita"
                value={horarioForm.duracionConsulta}
                onChange={(e) =>
                  setHorarioForm((f) => ({ ...f, duracionConsulta: e.target.value }))
                }
                options={DURACIONES.map((d) => ({ value: d, label: `${d} min` }))}
              />
              <div className="col-span-2 sm:col-span-4 flex justify-end gap-2">
                {editingHorarioId && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setEditingHorarioId(null);
                      setHorarioForm(EMPTY_HORARIO_FORM);
                      setHorarioFormError(null);
                    }}
                  >
                    Cancelar edición
                  </Button>
                )}
                <Button type="submit" variant="primary" size="sm" isLoading={isSavingHorario}>
                  {editingHorarioId ? 'Guardar bloque' : 'Agregar bloque'}
                </Button>
              </div>
            </form>
          )}
        </div>
      </Modal>
    </div>
  );
}
