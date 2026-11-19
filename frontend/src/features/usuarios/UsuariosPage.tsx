import { useState, useEffect, useMemo } from 'react';
import { api } from '@/lib/axios';
import { mensajeDelBackend, resumenDeErrorHttp } from '@/lib/apiError';
import { errorDeCorreoDeCuenta } from '@/lib/patronesDeFormato';
import {
  erroresDeCuenta,
  nombrePrincipalDeLaCuenta,
  tieneNombreDePersona,
  LONGITUD_MAXIMA_NOMBRE,
  type ErroresDeCuenta,
} from '@/features/usuarios/validacionUsuario';
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
import type { Usuario, RolUsuario } from '@/features/usuarios/usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import { useAuthStore } from '@/store/auth.store';
import {
  UserCog,
  UserCheck,
  UserX,
  Plus,
  Search,
  Pencil,
  Trash2,
  RotateCcw,
  Loader2,
  RefreshCw,
  Phone,
} from 'lucide-react';

const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de cuentas. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar los cambios. Revisa que el correo no esté en uso, que el rol MEDICO tenga un médico asociado, y que la organización conserve al menos una cuenta ADMIN activa.';
const MENSAJE_ERROR_BAJA =
  'No fue posible dar de baja la cuenta. Una cuenta no puede darse de baja a sí misma, y la organización necesita conservar al menos una cuenta ADMIN activa.';
const MENSAJE_ERROR_ACTIVAR =
  'No fue posible reactivar la cuenta. Revisa la conexión con el servicio e inténtalo de nuevo.';

// S6: la historia de usuario nombra tres roles —administrador, recepcionista y médico—, así que
// `PACIENTE` no se ofrece: no tiene ninguna celda en la matriz y la cuenta quedaría creada sin
// poder hacer nada. El tipo `RolUsuario` conserva los cuatro valores porque la base y las
// respuestas del backend los admiten; lo que cambia es lo que el selector **ofrece**.
const ROLES: RolUsuario[] = ['ADMIN', 'MEDICO', 'RECEPCIONISTA'];

const VARIANTE_POR_ROL: Record<RolUsuario, 'primary' | 'secondary' | 'warning' | 'default'> = {
  ADMIN: 'primary',
  MEDICO: 'secondary',
  RECEPCIONISTA: 'warning',
  PACIENTE: 'default',
};

const EMPTY_FORM = {
  nombre: '',
  telefono: '',
  email: '',
  rol: 'RECEPCIONISTA' as RolUsuario,
  medicoId: '',
};

/**
 * Mensajes del formulario: los de los datos de la persona y el de la forma del correo. La ausencia de
 * la clave significa que el campo cumple; el correo mantiene su regla propia, que es la del
 * identificador de inicio de sesión.
 */
type ErroresDelFormulario = ErroresDeCuenta & { email?: string };

/**
 * El correo es el identificador de inicio de sesión y el alta no se envía sin él.
 *
 * El formulario lleva `noValidate` para que los mensajes sean los de la pantalla —en español y junto
 * al campo— y no el aviso del navegador, que cada uno escribe en su idioma y bloquea el envío antes
 * de que este formulario pueda explicar qué falta. Por eso la obligatoriedad del correo también se
 * declara aquí, y no se delega en el atributo `required`.
 */
const MENSAJE_CORREO_OBLIGATORIO = 'El correo es obligatorio.';

export function UsuariosPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la
   * autoridad del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2,
   * guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  // Las seis operaciones son de ADMIN en el backend (UsuarioController); los demás roles caen en
  // el 403 de la matriz. La UI oculta las acciones de escritura para no ofrecer un botón que el
  // backend va a rechazar.
  const puedeEditar = useAuthStore((state) => state.user?.role === 'ADMIN');
  const emailDeSesion = useAuthStore((state) => state.user?.email?.toLowerCase());

  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/usuarios` : null;
  const rutaMedicos = organizacionId ? `/organizaciones/${organizacionId}/medicos` : null;

  const [usuarios, setUsuarios] = useState<Usuario[]>([]);
  const [medicos, setMedicos] = useState<Medico[]>([]);
  const [isLoading, setIsLoading] = useState(() => rutaColeccion !== null);
  const [loadError, setLoadError] = useState<string | null>(() =>
    rutaColeccion ? null : MENSAJE_ERROR_CARGA,
  );
  const [search, setSearch] = useState('');

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [erroresForm, setErroresForm] = useState<ErroresDelFormulario>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  // S1: el aviso del alta o de la edición vive dentro del diálogo, junto a los botones de acción.
  // La baja y la reactivación ocurren con el diálogo cerrado y tienen su propio aviso de página:
  // un único estado para los tres casos volvería invisibles dos de ellos.
  const [saveError, setSaveError] = useState<string | null>(null);
  const [bajaError, setBajaError] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);
  const [activatingId, setActivatingId] = useState<string | null>(null);

  const loadData = (ruta: string, rutaMedicos: string | null) => {
    return Promise.all([
      api.get<Usuario[]>(ruta),
      rutaMedicos ? api.get<Medico[]>(rutaMedicos) : null,
    ])
      .then(([usuariosRes, medicosRes]) => {
        setUsuarios(usuariosRes.data);
        setMedicos(medicosRes ? medicosRes.data : []);
        setLoadError(null);
      })
      .catch((err) => {
        console.error('Error al cargar cuentas:', resumenDeErrorHttp(err));
        setUsuarios([]);
        setLoadError(MENSAJE_ERROR_CARGA);
      })
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    if (rutaColeccion) loadData(rutaColeccion, rutaMedicos);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rutaColeccion]);

  const handleRetry = () => {
    if (!rutaColeccion) return;
    setIsLoading(true);
    setLoadError(null);
    loadData(rutaColeccion, rutaMedicos);
  };

  const medicoPorId = (id: string | null | undefined) => medicos.find((m) => m.id === id);

  const openCreateModal = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setErroresForm({});
    setSaveError(null);
    setIsModalOpen(true);
  };

  const openEditModal = (usuario: Usuario) => {
    setEditingId(usuario.id);
    setForm({
      // Una cuenta anterior a la V10 no tiene nombre: el campo nace vacío y guardarla obliga a
      // completarlo (S5). No se rellena con nada derivado del correo.
      nombre: usuario.nombre ?? '',
      telefono: usuario.telefono ?? '',
      email: usuario.email,
      rol: usuario.rol,
      medicoId: usuario.medicoId || '',
    });
    setErroresForm({});
    setSaveError(null);
    setIsModalOpen(true);
  };

  const esLaPropiaCuenta = (usuario: Usuario) => usuario.email.toLowerCase() === emailDeSesion;
  const editandoLaPropiaCuenta = editingId
    ? esLaPropiaCuenta(usuarios.find((u) => u.id === editingId) || ({} as Usuario))
    : false;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // S6: los espacios de los extremos no forman parte del correo. Se recorta antes de validar y
    // se envía recortado, porque un espacio invisible al pegar rechazaba un correo válido con un
    // mensaje sobre su forma —el mismo recorte que hace el backend antes de su patrón—. El nombre y
    // el teléfono se recortan por la misma razón: un espacio pegado no es parte del dato.
    const email = form.email.trim();
    const nombre = form.nombre.trim();
    const telefono = form.telefono.trim();

    // S1, S4 y S7: el nombre es obligatorio y el teléfono opcional, con las reglas compartidas y el
    // mensaje junto al campo. El correo conserva su regla estructural, que es la del identificador
    // de inicio de sesión. La misma validación corre en el backend.
    const errorEmail = !email ? MENSAJE_CORREO_OBLIGATORIO : errorDeCorreoDeCuenta(email);
    const errores: ErroresDelFormulario = {
      ...erroresDeCuenta({ nombre, telefono }),
      ...(errorEmail ? { email: errorEmail } : {}),
    };
    setErroresForm(errores);
    if (Object.keys(errores).length > 0) return;
    if (form.rol === 'MEDICO' && !form.medicoId) return;

    const payload = {
      email,
      rol: form.rol,
      medicoId: form.rol === 'MEDICO' ? form.medicoId : null,
      nombre,
      // El teléfono en blanco es «no registrado»: viaja nulo, igual que en el contrato del backend.
      telefono: telefono === '' ? null : telefono,
    };

    try {
      setIsSubmitting(true);
      setSaveError(null);
      if (editingId) {
        const res = await api.put<Usuario>(`/usuarios/${editingId}`, payload);
        setUsuarios((prev) => prev.map((u) => (u.id === editingId ? res.data : u)));
        setAlertNotice('Cuenta actualizada correctamente.');
      } else {
        if (!rutaColeccion) return;
        const res = await api.post<Usuario>(rutaColeccion, payload);
        setUsuarios((prev) => [...prev, res.data].sort((a, b) => a.email.localeCompare(b.email)));
        setAlertNotice('Cuenta registrada correctamente.');
      }
      setIsModalOpen(false);
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar la cuenta:', resumenDeErrorHttp(err));
      // El rechazo del servicio explica el motivo —el correo ya está en uso, o su forma no es
      // válida— y se muestra tal cual; sin mensaje se cae al texto de la pantalla.
      setSaveError(mensajeDelBackend(err, MENSAJE_ERROR_GUARDADO));
    } finally {
      setIsSubmitting(false);
    }
  };

  // No hay confirmación proactiva de "última cuenta ADMIN": el backend es la autoridad de esa
  // regla y la UI solo puede mostrar el 409 que ya responde.
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    setBajaError(null);
    try {
      setIsDeactivating(true);
      await api.delete(`/usuarios/${deactivatingId}`);
      setUsuarios((prev) =>
        prev.map((u) => (u.id === deactivatingId ? { ...u, activo: false } : u)),
      );
      setAlertNotice('Cuenta dada de baja correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al dar de baja la cuenta:', resumenDeErrorHttp(err));
      setAlertNotice(null);
      setBajaError(MENSAJE_ERROR_BAJA);
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

  const handleActivate = async (id: string) => {
    setReactivarError(null);
    try {
      setActivatingId(id);
      await api.post(`/usuarios/${id}/activar`);
      setUsuarios((prev) => prev.map((u) => (u.id === id ? { ...u, activo: true } : u)));
      setAlertNotice('Cuenta reactivada correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al reactivar la cuenta:', resumenDeErrorHttp(err));
      setReactivarError(MENSAJE_ERROR_ACTIVAR);
    } finally {
      setActivatingId(null);
    }
  };

  const filteredUsuarios = useMemo(() => {
    const term = search.toLowerCase();
    // S4: la lista muestra personas, así que la búsqueda tiene que encontrar por nombre además de
    // por correo; dejar solo el correo haría invisible a quien busca por el nombre que está viendo.
    return usuarios.filter(
      (u) =>
        !term ||
        u.email.toLowerCase().includes(term) ||
        (u.nombre ?? '').toLowerCase().includes(term),
    );
  }, [usuarios, search]);

  const stats = useMemo(() => {
    const total = usuarios.length;
    const activas = usuarios.filter((u) => u.activo).length;
    return { total, activas, inactivas: total - activas };
  }, [usuarios]);

  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  const usuarioADesactivar = usuarios.find((u) => u.id === deactivatingId);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
            Usuarios
          </h1>
          <p className="text-xs text-[#64748B] mt-1">
            Cuentas del personal de la organización y sus roles de acceso.
          </p>
        </div>

        {puedeEditar && (
          <Button
            variant="primary"
            size="sm"
            onClick={openCreateModal}
            leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
          >
            Nueva Cuenta
          </Button>
        )}
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}
      {/* S1: la baja y la reactivación ocurren con el diálogo cerrado, así que sus avisos se leen
          en la página. El del alta y la edición se pinta dentro del modal (ver abajo). */}
      {bajaError && (
        <Alert variant="error" title="No se pudo dar de baja la cuenta">
          {bajaError}
        </Alert>
      )}
      {reactivarError && (
        <Alert variant="error" title="No se pudo reactivar la cuenta">
          {reactivarError}
        </Alert>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Cuentas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <UserCog className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Activas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.activas)}
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
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Inactivas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#41484D] mt-1 tnum">
                {metrica(stats.inactivas)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#F1F5F9] text-[#64748B] flex items-center justify-center">
              <UserX className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre o correo..."
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
              Mostrando <strong>{filteredUsuarios.length}</strong> cuentas registradas
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Persona</TableHead>
              <TableHead>Rol</TableHead>
              <TableHead>Médico asociado</TableHead>
              <TableHead>Estado</TableHead>
              {puedeEditar && <TableHead className="text-right">Acciones</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={5} className="text-center py-12">
                  <span
                    role="status"
                    aria-live="polite"
                    className="inline-flex items-center gap-2 text-[#64748B]"
                  >
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Cargando cuentas...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar las cuentas">
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
            ) : filteredUsuarios.length > 0 ? (
              filteredUsuarios.map((u) => (
                <TableRow key={u.id}>
                  <TableCell>
                    {/*
                      S4: el dato principal es la persona. Una cuenta anterior a la V10 no tiene
                      nombre y muestra el correo tal cual: no se deriva un nombre de la dirección.
                      El correo sigue visible como dato secundario cuando hay nombre, porque es con
                      lo que la persona inicia sesión.
                    */}
                    <span className="text-xs font-semibold text-[#0B1C30] block">
                      {nombrePrincipalDeLaCuenta(u)}
                    </span>
                    {tieneNombreDePersona(u) && (
                      <span className="text-[11px] text-[#64748B] block">{u.email}</span>
                    )}
                    {esLaPropiaCuenta(u) && (
                      <span className="text-[10px] text-[#64748B] block">Tu cuenta</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant={VARIANTE_POR_ROL[u.rol]} showDot={false}>
                      {u.rol}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <span className="text-xs text-[#41484D]">
                      {medicoPorId(u.medicoId)?.nombre || '—'}
                    </span>
                  </TableCell>
                  <TableCell>
                    <Badge variant={u.activo ? 'success' : 'default'} showDot>
                      {u.activo ? 'Activa' : 'Inactiva'}
                    </Badge>
                  </TableCell>
                  {puedeEditar && (
                    <TableCell className="text-right">
                      <div className="inline-flex items-center gap-1.5">
                        {u.activo ? (
                          <>
                            <button
                              type="button"
                              onClick={() => openEditModal(u)}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                              title="Editar cuenta"
                            >
                              <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                              <span className="hidden sm:inline">Editar</span>
                            </button>
                            {!esLaPropiaCuenta(u) && (
                              <button
                                type="button"
                                onClick={() => setDeactivatingId(u.id)}
                                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                                title="Dar de baja"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                                <span className="hidden sm:inline">Baja</span>
                              </button>
                            )}
                          </>
                        ) : (
                          <Button
                            variant="outline"
                            size="sm"
                            isLoading={activatingId === u.id}
                            onClick={() => handleActivate(u.id)}
                            leftIcon={<RotateCcw className="w-3.5 h-3.5" />}
                          >
                            Reactivar
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  )}
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={5} className="text-center py-12">
                  {search ? (
                    <span className="text-[#94A3B8]">
                      No se encontraron cuentas con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <UserCog className="w-4 h-4 text-[#64748B]" />
                        Aún no hay cuentas registradas
                      </p>
                      {puedeEditar && (
                        <p className="text-[#94A3B8]">
                          Da de alta la primera cuenta con el botón «Nueva Cuenta».
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
          MODAL DE CUENTA (CREAR / EDITAR)
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingId ? 'Editar Cuenta' : 'Registrar Nueva Cuenta'}
        description="Datos de la persona, correo de acceso y rol."
        maxWidth="md"
      >
        {/*
          `noValidate`: los mensajes son los de la pantalla —en español y junto al campo—, no el
          aviso del navegador, que cada uno escribe en su idioma y bloquea el envío antes de que
          este formulario pueda explicar qué falta. Es el mismo criterio del registro de clínica.
        */}
        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          {/*
            S1 y S5: el nombre es obligatorio y el teléfono opcional, en el alta y en la edición.
            Una cuenta anterior a la V10 nace con el nombre en blanco y no se guarda hasta
            completarlo: la lista muestra personas, no identificadores.
          */}
          <Input
            label="Nombre Completo"
            type="text"
            autoComplete="name"
            value={form.nombre}
            onChange={(e) => {
              setForm((f) => ({ ...f, nombre: e.target.value }));
              setErroresForm((prev) => ({ ...prev, nombre: undefined }));
            }}
            error={erroresForm.nombre}
            required
            maxLength={LONGITUD_MAXIMA_NOMBRE}
          />
          <Input
            label="Teléfono"
            type="text"
            inputMode="tel"
            autoComplete="tel"
            value={form.telefono}
            onChange={(e) => {
              setForm((f) => ({ ...f, telefono: e.target.value }));
              setErroresForm((prev) => ({ ...prev, telefono: undefined }));
            }}
            error={erroresForm.telefono}
            leftIcon={<Phone className="w-4 h-4" />}
            helperText="Opcional."
          />
          {/*
            `type="text"` con `inputMode="email"` en lugar de `type="email"`: el navegador
            rechazaría por su cuenta parte de los correos sin forma de correo (`juan@`, `juan@.com`)
            con un aviso en su propio idioma y sin llegar a este formulario, y la regla —con su
            mensaje en español, junto al campo— vive aquí y en el backend. El teclado del móvil y
            el autocompletado se conservan con `inputMode` y `autoComplete`.
          */}
          <Input
            label="Correo"
            type="text"
            inputMode="email"
            autoComplete="email"
            value={form.email}
            onChange={(e) => {
              setForm((f) => ({ ...f, email: e.target.value }));
              setErroresForm((prev) => ({ ...prev, email: undefined }));
            }}
            error={erroresForm.email}
            required
            maxLength={180}
          />
          <Select
            label="Rol"
            value={form.rol}
            onChange={(e) => setForm((f) => ({ ...f, rol: e.target.value as RolUsuario }))}
            options={ROLES.map((r) => ({ value: r, label: r }))}
            disabled={editandoLaPropiaCuenta}
            helperText={
              editandoLaPropiaCuenta
                ? 'No puedes cambiarte el rol a ti mismo.'
                : undefined
            }
          />
          {form.rol === 'MEDICO' && (
            <Select
              label="Médico asociado"
              value={form.medicoId}
              onChange={(e) => setForm((f) => ({ ...f, medicoId: e.target.value }))}
              options={medicos.map((m) => ({ value: m.id, label: m.nombre }))}
              placeholder="Selecciona..."
              required
            />
          )}

          {saveError && (
            <Alert variant="error" title="No se pudo guardar la cuenta">
              {saveError}
            </Alert>
          )}

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsModalOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSubmitting}>
              {editingId ? 'Guardar Cambios' : 'Registrar Cuenta'}
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
        title="Dar de baja esta cuenta"
        description="La cuenta deja de poder iniciar sesión; se puede reactivar más adelante."
        maxWidth="sm"
      >
        <p className="text-sm text-[#41484D]">
          ¿Dar de baja <strong>{usuarioADesactivar?.email}</strong>?
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
            Dar de baja
          </Button>
        </div>
      </Modal>
    </div>
  );
}
