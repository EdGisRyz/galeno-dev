import { useRef, useState } from 'react';
import { Button, Modal, Alert } from '@/components/ui';
import { Plus, Trash2 } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import { useAuthStore } from '@/store/auth.store';
import type { Consultorio } from './consultorios.types';
import type { ConsultorioPayload } from './consultorios.service';
import {
  useConsultoriosDirectorio,
  useCrearConsultorio,
  useActualizarConsultorio,
  useActivarConsultorio,
  useDesactivarConsultorio,
} from './consultorios.queries';
import { ConsultorioTable } from './ConsultorioTable';
import { ConsultorioForm } from './ConsultorioForm';

const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de consultorios. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_REACTIVAR =
  'No fue posible reactivar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Pantalla de Consultorios: directorio, alta/edición, baja y reactivación.
 *
 * Es el orquestador (H11): la lectura y las mutaciones viven en `consultorios.queries.ts`, las
 * llamadas HTTP en `consultorios.service.ts`, y la interfaz se reparte en `ConsultorioTable`
 * (directorio) y `ConsultorioForm` (alta/edición).
 */
export function ConsultoriosPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la autoridad
   * del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2, guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  // Alta, edición y baja son de ADMIN en el backend (ConsultorioController); MEDICO solo lee.
  const puedeEditar = useAuthStore((state) => state.user?.role === 'ADMIN');

  /**
   * La colección cuelga de la organización dueña: `GET/POST /organizaciones/{id}/consultorios`. El
   * ítem es plano (`PUT/DELETE /consultorios/{id}`), así que solo la colección necesita la
   * organización. Sin claim no hay URL que pedir.
   */
  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/consultorios` : null;

  const directorio = useConsultoriosDirectorio(rutaColeccion);
  const consultorios = directorio.data ?? [];
  const loadError = directorio.isError ? MENSAJE_ERROR_CARGA : null;

  const crearConsultorio = useCrearConsultorio(rutaColeccion);
  const actualizarConsultorio = useActualizarConsultorio(rutaColeccion);
  const activarConsultorio = useActivarConsultorio(rutaColeccion);
  const desactivarConsultorio = useDesactivarConsultorio(rutaColeccion);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingConsultorio, setEditingConsultorio] = useState<Consultorio | null>(null);
  // Cambia en cada apertura para que `ConsultorioForm` monte una instancia nueva (ver su
  // comentario): así arranca limpio sin necesitar un efecto que lo reinicie.
  const [formKey, setFormKey] = useState(0);
  const [bajaError, setBajaError] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);

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
    setEditingConsultorio(null);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const openEditModal = (consultorio: Consultorio) => {
    setEditingConsultorio(consultorio);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const handleFormSubmit = async (payload: ConsultorioPayload) => {
    if (editingConsultorio) {
      await actualizarConsultorio.mutateAsync({ id: editingConsultorio.id, payload });
      setAlertNotice('Consultorio actualizado correctamente.');
    } else {
      await crearConsultorio.mutateAsync(payload);
      setAlertNotice('Consultorio registrado correctamente.');
    }
    setIsModalOpen(false);
    programarOcultarAviso();
  };

  /**
   * Reactivación de una baja lógica (S4): `POST /consultorios/{id}/activar` responde 204 sin
   * cuerpo, así que la fila se marca activa en la caché. Es el consultorio que se dio de baja por
   * una refacción y vuelve. Un rechazo del backend se muestra con su mensaje.
   */
  const handleReactivar = async (consultorio: Consultorio) => {
    setReactivarError(null);
    try {
      await activarConsultorio.mutateAsync(consultorio.id);
      setAlertNotice('Consultorio reactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      setReactivarError(mensajeDelBackend(err, MENSAJE_ERROR_REACTIVAR));
    }
  };

  /**
   * Baja lógica del consultorio: `DELETE /consultorios/{id}` responde 204 sin cuerpo. El backend
   * rechaza con 409 la baja del consultorio principal de un médico activo (S6) y su mensaje dice
   * el camino —dar de baja primero al médico—; se muestra tal cual.
   */
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    setBajaError(null);
    try {
      setIsDeactivating(true);
      await desactivarConsultorio.mutateAsync(deactivatingId);
      setAlertNotice('Consultorio desactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      setAlertNotice(null);
      setBajaError(mensajeDelBackend(err, MENSAJE_ERROR_BAJA));
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

  const consultorioADesactivar = consultorios.find((c) => c.id === deactivatingId);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
            Consultorios
          </h1>
          <p className="text-xs text-[#64748B] mt-1">
            Directorio de consultorios y espacios físicos de la clínica.
          </p>
        </div>

        {puedeEditar && (
          <Button
            variant="primary"
            size="sm"
            onClick={openCreateModal}
            leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
          >
            Nuevo Consultorio
          </Button>
        )}
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}
      {/* S1: la baja ocurre con el diálogo cerrado, así que su aviso se lee en la página. El del
          alta y la edición se pinta dentro del modal (`ConsultorioForm`). */}
      {bajaError && (
        <Alert variant="error" title="No se pudo desactivar el consultorio">
          {bajaError}
        </Alert>
      )}
      {reactivarError && (
        <Alert variant="error" title="No se pudo reactivar el consultorio">
          {reactivarError}
        </Alert>
      )}

      <ConsultorioTable
        consultorios={consultorios}
        isLoading={directorio.isLoading}
        loadError={loadError}
        onRetry={() => directorio.refetch()}
        puedeEditar={puedeEditar}
        onEdit={openEditModal}
        onReactivate={handleReactivar}
        onDeactivateRequest={setDeactivatingId}
      />

      {isModalOpen && (
        <ConsultorioForm
          key={formKey}
          onClose={() => setIsModalOpen(false)}
          editingConsultorio={editingConsultorio}
          onSubmit={handleFormSubmit}
        />
      )}

      {/* ─────────────────────────────────────────
          MODAL DE CONFIRMACIÓN DE BAJA
          ───────────────────────────────────────── */}
      <Modal
        isOpen={deactivatingId !== null}
        onClose={() => setDeactivatingId(null)}
        title="Desactivar consultorio"
        description="El consultorio deja de estar disponible para nuevas citas; su historial se conserva."
        maxWidth="sm"
      >
        <p className="text-sm text-[#41484D]">
          ¿Desactivar <strong>{consultorioADesactivar?.nombre}</strong>? Dejará de estar disponible
          para nuevas citas y podrás reactivarlo desde esta misma pantalla cuando vuelva.
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
    </div>
  );
}
