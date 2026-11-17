import { useState, useEffect, useMemo } from 'react';
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
  email: '',
  rol: 'RECEPCIONISTA' as RolUsuario,
  medicoId: '',
};

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
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
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
    setSaveError(null);
    setIsModalOpen(true);
  };

  const openEditModal = (usuario: Usuario) => {
    setEditingId(usuario.id);
    setForm({
      email: usuario.email,
      rol: usuario.rol,
      medicoId: usuario.medicoId || '',
    });
    setSaveError(null);
    setIsModalOpen(true);
  };

  const esLaPropiaCuenta = (usuario: Usuario) => usuario.email.toLowerCase() === emailDeSesion;
  const editandoLaPropiaCuenta = editingId
    ? esLaPropiaCuenta(usuarios.find((u) => u.id === editingId) || ({} as Usuario))
    : false;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.email.trim()) return;
    if (form.rol === 'MEDICO' && !form.medicoId) return;

    const payload = {
      email: form.email,
      rol: form.rol,
      medicoId: form.rol === 'MEDICO' ? form.medicoId : null,
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
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSubmitting(false);
    }
  };

  // No hay confirmación proactiva de "última cuenta ADMIN": el backend es la autoridad de esa
  // regla y la UI solo puede mostrar el 409 que ya responde.
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
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
      setSaveError(MENSAJE_ERROR_BAJA);
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

  const handleActivate = async (id: string) => {
    try {
      setActivatingId(id);
      await api.post(`/usuarios/${id}/activar`);
      setUsuarios((prev) => prev.map((u) => (u.id === id ? { ...u, activo: true } : u)));
      setAlertNotice('Cuenta reactivada correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al reactivar la cuenta:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_ACTIVAR);
    } finally {
      setActivatingId(null);
    }
  };

  const filteredUsuarios = useMemo(() => {
    const term = search.toLowerCase();
    return usuarios.filter((u) => !term || u.email.toLowerCase().includes(term));
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
      {saveError && (
        <Alert variant="error" title="Error">
          {saveError}
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
            placeholder="Buscar por correo..."
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
              <TableHead>Correo</TableHead>
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
                    <span className="text-xs font-semibold text-[#0B1C30]">{u.email}</span>
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
        description="Correo, rol y médico asociado (si aplica)."
        maxWidth="md"
      >
        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Correo"
            type="email"
            value={form.email}
            onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
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
