import { useRef, useState } from 'react';
import { Button, Modal, Alert } from '@/components/ui';
import { Plus, Trash2 } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import { useAuthStore } from '@/store/auth.store';
import type { Medico } from './medicos.types';
import type { MedicoPayload } from './medicos.service';
import {
  useMedicosDirectorio,
  useCrearMedico,
  useActualizarMedico,
  useActivarMedico,
  useDesactivarMedico,
} from './medicos.queries';
import { MedicoTable } from './MedicoTable';
import { MedicoForm } from './MedicoForm';
import { HorarioEditor } from './HorarioEditor';

const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de médicos. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar el médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_REACTIVAR =
  'No fue posible reactivar al médico. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Pantalla de Médicos: directorio, alta/edición, baja y reactivación, y el panel de horarios de
 * disponibilidad con sus consultorios asignados.
 *
 * Es el orquestador (H11): la lectura y las mutaciones viven en `medicos.queries.ts`, las llamadas
 * HTTP en `medicos.service.ts`, y la interfaz se reparte en `MedicoTable` (directorio),
 * `MedicoForm` (alta/edición) y `HorarioEditor` (agenda y consultorios asignados).
 */
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
  const puedeEditarMedicos = rolDeSesion === 'ADMIN';

  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/medicos` : null;
  const rutaConsultorios = organizacionId ? `/organizaciones/${organizacionId}/consultorios` : null;

  const directorio = useMedicosDirectorio(rutaColeccion, rutaConsultorios);
  const medicos = directorio.data?.medicos ?? [];
  const consultorios = directorio.data?.consultorios ?? [];
  const loadError = directorio.isError ? MENSAJE_ERROR_CARGA : null;

  const crearMedico = useCrearMedico(rutaColeccion, rutaConsultorios);
  const actualizarMedico = useActualizarMedico(rutaColeccion, rutaConsultorios);
  const activarMedico = useActivarMedico(rutaColeccion, rutaConsultorios);
  const desactivarMedico = useDesactivarMedico(rutaColeccion, rutaConsultorios);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingMedico, setEditingMedico] = useState<Medico | null>(null);
  // Cambia en cada apertura para que `MedicoForm` monte una instancia nueva (ver su comentario):
  // así arranca limpio sin necesitar un efecto que lo reinicie.
  const [formKey, setFormKey] = useState(0);
  const [bajaError, setBajaError] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);
  const [horariosDeId, setHorariosDeId] = useState<string | null>(null);

  /**
   * Los avisos de éxito se ocultan solos. El temporizador se guarda en una referencia para poder
   * cancelarlo: uno pendiente intentaría escribir el estado de un componente ya desmontado.
   */
  const avisoRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const programarOcultarAviso = () => {
    if (avisoRef.current) clearTimeout(avisoRef.current);
    avisoRef.current = setTimeout(() => setAlertNotice(null), 3500);
  };

  // El directorio trae también los consultorios dados de baja para nombrarlos en la tabla (S12),
  // pero el alta no debe ofrecer uno cerrado: los selectores usan solo los activos.
  const consultoriosDisponibles = consultorios.filter((c) => c.activo);

  const openCreateModal = () => {
    setEditingMedico(null);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const openEditModal = (medico: Medico) => {
    setEditingMedico(medico);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const handleFormSubmit = async (payload: MedicoPayload) => {
    if (editingMedico) {
      // El consultorio no se puede cambiar por esta vía: el backend responde 400 si difiere del
      // actual (R13). `MedicoForm` mantiene el campo bloqueado al editar para no ofrecer ese cambio.
      await actualizarMedico.mutateAsync({ id: editingMedico.id, payload });
      setAlertNotice('Médico actualizado correctamente.');
    } else {
      await crearMedico.mutateAsync(payload);
      setAlertNotice('Médico registrado correctamente.');
    }
    setIsModalOpen(false);
    programarOcultarAviso();
  };

  /**
   * Reactivación de una baja lógica (S4): `POST /medicos/{id}/activar` responde 204 sin cuerpo, así
   * que la fila se marca activa en la caché. Un rechazo del backend —por ejemplo, el consultorio
   * principal dado de baja (S7)— se muestra con su mensaje, porque es lo único que dice qué hacer
   * antes de reintentar.
   */
  const handleReactivar = async (medico: Medico) => {
    setReactivarError(null);
    try {
      await activarMedico.mutateAsync(medico.id);
      setAlertNotice('Médico reactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      setReactivarError(mensajeDelBackend(err, MENSAJE_ERROR_REACTIVAR));
    }
  };

  /**
   * Baja lógica del médico: `DELETE /medicos/{id}` responde 204 sin cuerpo, así que la fila se
   * marca inactiva en la caché y aparece la acción para reactivarlo.
   */
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    setBajaError(null);
    try {
      setIsDeactivating(true);
      await desactivarMedico.mutateAsync(deactivatingId);
      setAlertNotice('Médico desactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      setAlertNotice(null);
      setBajaError(mensajeDelBackend(err, MENSAJE_ERROR_BAJA));
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

  /**
   * Agendas que la sesión puede **leer**: las de toda la organización para administración y
   * recepción, y la propia para un médico (ADR-013, matriz del backend). Ofrecerlo en una fila
   * ajena abría un panel que el servicio rechaza con 403 (S4), y en una fila inactiva llevaría a
   * un 409 predecible: su agenda no es editable (S10).
   */
  const puedeAbrirAgendaDe = (medico: Medico) => {
    const puedeConsultarLaAgenda =
      rolDeSesion === 'ADMIN' ||
      rolDeSesion === 'RECEPCIONISTA' ||
      (rolDeSesion === 'MEDICO' && medicoIdDeSesion === medico.id);
    return medico.activo && puedeConsultarLaAgenda;
  };

  const medicoADesactivar = medicos.find((m) => m.id === deactivatingId);
  const medicoDeHorarios = medicos.find((m) => m.id === horariosDeId);

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
            disabled={consultoriosDisponibles.length === 0}
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
      {/* S1: la baja ocurre con el diálogo cerrado, así que su aviso se lee en la página. El del
          alta y la edición se pinta dentro del modal (`MedicoForm`). */}
      {bajaError && (
        <Alert variant="error" title="No se pudo desactivar el médico">
          {bajaError}
        </Alert>
      )}
      {reactivarError && (
        <Alert variant="error" title="No se pudo reactivar al médico">
          {reactivarError}
        </Alert>
      )}

      <MedicoTable
        medicos={medicos}
        consultorios={consultorios}
        isLoading={directorio.isLoading}
        loadError={loadError}
        onRetry={() => directorio.refetch()}
        puedeEditarMedicos={puedeEditarMedicos}
        sinConsultoriosDisponibles={consultoriosDisponibles.length === 0}
        onEdit={openEditModal}
        onReactivate={handleReactivar}
        onDeactivateRequest={setDeactivatingId}
        puedeAbrirAgendaDe={puedeAbrirAgendaDe}
        onOpenHorarios={setHorariosDeId}
      />

      {isModalOpen && (
        <MedicoForm
          key={formKey}
          onClose={() => setIsModalOpen(false)}
          editingMedico={editingMedico}
          consultoriosDisponibles={consultoriosDisponibles}
          onSubmit={handleFormSubmit}
        />
      )}

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
          ¿Desactivar <strong>{medicoADesactivar?.nombre}</strong>? Dejará de estar disponible
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
        {horariosDeId && medicoDeHorarios && (
          <HorarioEditor medico={medicoDeHorarios} consultoriosOrganizacion={consultorios} />
        )}
      </Modal>
    </div>
  );
}
