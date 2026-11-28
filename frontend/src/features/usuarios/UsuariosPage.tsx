import { useRef, useState } from 'react';
import { Button, Modal, Alert } from '@/components/ui';
import { Plus, Trash2 } from 'lucide-react';
import { useAuthStore } from '@/store/auth.store';
import type { Usuario } from './usuarios.types';
import type { UsuarioPayload } from './usuarios.service';
import {
  useUsuariosDirectorio,
  useCrearUsuario,
  useActualizarUsuario,
  useActivarUsuario,
  useDesactivarUsuario,
} from './usuarios.queries';
import { UsuarioTable } from './UsuarioTable';
import { UsuarioForm } from './UsuarioForm';

const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de cuentas. Revisa la conexión con el servicio e inténtalo de nuevo.';
/**
 * Mensaje fijo, no el motivo exacto del backend: cubre las dos reglas que puede rechazar la baja
 * (403 sobre la propia cuenta, 409 sobre la última cuenta ADMIN activa) sin tener que distinguir
 * cuál de las dos fue.
 */
const MENSAJE_ERROR_BAJA =
  'No fue posible dar de baja la cuenta. Una cuenta no puede darse de baja a sí misma, y la organización necesita conservar al menos una cuenta ADMIN activa.';
const MENSAJE_ERROR_ACTIVAR =
  'No fue posible reactivar la cuenta. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Pantalla de Cuentas: directorio, alta/edición, baja y reactivación.
 *
 * Es el orquestador (H11): la lectura y las mutaciones viven en `usuarios.queries.ts`, las
 * llamadas HTTP en `usuarios.service.ts`, y la interfaz se reparte en `UsuarioTable` (directorio)
 * y `UsuarioForm` (alta/edición).
 */
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

  const directorio = useUsuariosDirectorio(rutaColeccion, rutaMedicos);
  const usuarios = directorio.data?.usuarios ?? [];
  const medicos = directorio.data?.medicos ?? [];
  const loadError = directorio.isError ? MENSAJE_ERROR_CARGA : null;

  const crearUsuario = useCrearUsuario(rutaColeccion, rutaMedicos);
  const actualizarUsuario = useActualizarUsuario(rutaColeccion, rutaMedicos);
  const activarUsuario = useActivarUsuario(rutaColeccion, rutaMedicos);
  const desactivarUsuario = useDesactivarUsuario(rutaColeccion, rutaMedicos);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingUsuario, setEditingUsuario] = useState<Usuario | null>(null);
  // Cambia en cada apertura para que `UsuarioForm` monte una instancia nueva (ver su comentario):
  // así arranca limpio sin necesitar un efecto que lo reinicie.
  const [formKey, setFormKey] = useState(0);
  // S1: el aviso del alta o de la edición vive dentro del diálogo (`UsuarioForm`). La baja y la
  // reactivación ocurren con el diálogo cerrado y tienen su propio aviso de página: un único
  // estado para los tres casos volvería invisibles dos de ellos.
  const [bajaError, setBajaError] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);
  const [activatingId, setActivatingId] = useState<string | null>(null);

  /**
   * Los avisos de éxito se ocultan solos. El temporizador se guarda en una referencia para poder
   * cancelarlo: uno pendiente intentaría escribir el estado de un componente ya desmontado.
   */
  const avisoRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const programarOcultarAviso = () => {
    if (avisoRef.current) clearTimeout(avisoRef.current);
    avisoRef.current = setTimeout(() => setAlertNotice(null), 3500);
  };

  const openCreateModal = () => {
    setEditingUsuario(null);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const openEditModal = (usuario: Usuario) => {
    setEditingUsuario(usuario);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const esLaPropiaCuenta = (usuario: Usuario) => usuario.email.toLowerCase() === emailDeSesion;
  const editandoLaPropiaCuenta = editingUsuario ? esLaPropiaCuenta(editingUsuario) : false;

  const handleFormSubmit = async (payload: UsuarioPayload) => {
    if (editingUsuario) {
      await actualizarUsuario.mutateAsync({ id: editingUsuario.id, payload });
      setAlertNotice('Cuenta actualizada correctamente.');
    } else {
      await crearUsuario.mutateAsync(payload);
      setAlertNotice('Cuenta registrada correctamente.');
    }
    setIsModalOpen(false);
    programarOcultarAviso();
  };

  // No hay confirmación proactiva de "última cuenta ADMIN": el backend es la autoridad de esa
  // regla y la UI solo puede mostrar el 409 que ya responde.
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    setBajaError(null);
    try {
      setIsDeactivating(true);
      await desactivarUsuario.mutateAsync(deactivatingId);
      setAlertNotice('Cuenta dada de baja correctamente.');
      programarOcultarAviso();
    } catch {
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
      await activarUsuario.mutateAsync(id);
      setAlertNotice('Cuenta reactivada correctamente.');
      programarOcultarAviso();
    } catch {
      setReactivarError(MENSAJE_ERROR_ACTIVAR);
    } finally {
      setActivatingId(null);
    }
  };

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
          en la página. El del alta y la edición se pinta dentro del modal (`UsuarioForm`). */}
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

      <UsuarioTable
        usuarios={usuarios}
        medicos={medicos}
        isLoading={directorio.isLoading}
        loadError={loadError}
        onRetry={() => directorio.refetch()}
        puedeEditar={puedeEditar}
        emailDeSesion={emailDeSesion}
        activatingId={activatingId}
        onEdit={openEditModal}
        onActivate={handleActivate}
        onDeactivateRequest={setDeactivatingId}
      />

      {isModalOpen && (
        <UsuarioForm
          key={formKey}
          onClose={() => setIsModalOpen(false)}
          editingUsuario={editingUsuario}
          editandoLaPropiaCuenta={editandoLaPropiaCuenta}
          medicos={medicos}
          onSubmit={handleFormSubmit}
        />
      )}

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
