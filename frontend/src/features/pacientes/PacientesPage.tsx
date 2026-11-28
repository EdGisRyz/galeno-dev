import { useRef, useState } from 'react';
import { Button, Alert } from '@/components/ui';
import { UserPlus } from 'lucide-react';
import { useAuthStore } from '@/store/auth.store';
import type { Paciente } from './pacientes.types';
import type { PacientePayload } from './pacientes.service';
import {
  usePacientesDirectorio,
  useCrearPaciente,
  useActualizarPaciente,
  useActivarPaciente,
  useDesactivarPaciente,
} from './pacientes.queries';
import { PacienteTable } from './PacienteTable';
import { PacienteForm } from './PacienteForm';

/** Mensaje único del estado de error de carga: el detalle técnico solo va a la consola. */
const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de pacientes. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Fallo propio de la reactivación y de la baja: un mensaje fijo, no el de carga, que hablaría de
 * leer el directorio y confundiría sobre qué acción no se completó.
 */
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar al paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_REACTIVAR =
  'No fue posible reactivar al paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';

/**
 * Pantalla de Pacientes: directorio, alta/edición de la ficha NOM-004, baja y reactivación.
 *
 * Es el orquestador (H11): la lectura y las mutaciones viven en `pacientes.queries.ts`, las
 * llamadas HTTP en `pacientes.service.ts`, y la interfaz se reparte en `PacienteTable`
 * (directorio) y `PacienteForm` (alta/edición).
 */
export function PacientesPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la
   * autoridad del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2,
   * guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  // Las escrituras de paciente son de administración y recepción; el médico la consulta. Ofrecerle
  // botones que el backend rechaza con 403 es peor que no mostrarlos: la pantalla promete algo que
  // el servidor va a negar.
  const rolDeSesion = useAuthStore((state) => state.user?.role);
  const puedeGestionar = rolDeSesion === 'ADMIN' || rolDeSesion === 'RECEPCIONISTA';

  /**
   * La colección cuelga de la organización dueña: `GET/POST /organizaciones/{id}/pacientes`. El
   * ítem del paciente es plano (`PUT /pacientes/{id}`), así que solo la colección necesita la
   * organización. Sin claim no hay URL que pedir.
   */
  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/pacientes` : null;

  const directorio = usePacientesDirectorio(rutaColeccion);
  const pacientes = directorio.data ?? [];
  // Sin organización en la sesión la pantalla muestra el error de inmediato: no hay petición que
  // hacer ni se muestra un directorio vacío que afirmaría «no hay pacientes». Mientras se vuelve a
  // pedir (carga inicial o reintento), `isFetching` manda sobre un error todavía vigente en la
  // caché: así la tarjeta de error no convive con el spinner de "Cargando...".
  const loadError = rutaColeccion === null || directorio.isError ? MENSAJE_ERROR_CARGA : null;

  const crearPaciente = useCrearPaciente(rutaColeccion);
  const actualizarPaciente = useActualizarPaciente(rutaColeccion);
  const activarPaciente = useActivarPaciente(rutaColeccion);
  const desactivarPaciente = useDesactivarPaciente(rutaColeccion);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingPaciente, setEditingPaciente] = useState<Paciente | null>(null);
  // Cambia en cada apertura para que `PacienteForm` monte una instancia nueva (ver su
  // comentario): así arranca limpio sin necesitar un efecto que lo reinicie.
  const [formKey, setFormKey] = useState(0);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);
  const [reactivarError, setReactivarError] = useState<string | null>(null);
  const [bajaError, setBajaError] = useState<string | null>(null);

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
    setEditingPaciente(null);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const openEditModal = (patient: Paciente) => {
    setEditingPaciente(patient);
    setFormKey((k) => k + 1);
    setIsModalOpen(true);
  };

  const handleFormSubmit = async (payload: PacientePayload) => {
    if (editingPaciente) {
      // El ítem es plano: la organización dueña no se envía ni se puede cambiar.
      await actualizarPaciente.mutateAsync({ id: editingPaciente.id, payload });
      setAlertNotice('Ficha del paciente actualizada correctamente.');
    } else {
      await crearPaciente.mutateAsync(payload);
      setAlertNotice('Paciente registrado correctamente.');
    }
    setIsModalOpen(false);
    programarOcultarAviso();
  };

  /**
   * Reactivación de una baja lógica (S5): `POST /pacientes/{id}/activar` responde 204 sin cuerpo,
   * así que la fila se marca activa en la caché y no se vuelve a pedir el directorio. El listado
   * incluye a los inactivos gracias al parámetro `incluirInactivos`, así que la fila sigue visible
   * después de recargar y la reactivación no obliga a un alta nueva.
   */
  const handleReactivar = async (paciente: Paciente) => {
    setReactivarError(null);
    try {
      await activarPaciente.mutateAsync(paciente.id);
      setAlertNotice('Paciente reactivado correctamente.');
      programarOcultarAviso();
    } catch {
      setReactivarError(MENSAJE_ERROR_REACTIVAR);
    }
  };

  /**
   * Baja lógica del paciente: `DELETE /pacientes/{id}` responde 204 sin cuerpo y el servicio solo
   * apaga el registro, así que la fila se marca inactiva en la caché y aparece la acción para
   * reactivarla. No se pide confirmación: la baja es reversible en un clic y el historial se
   * conserva, que es justamente el motivo de que sea lógica.
   */
  const handleDesactivar = async (paciente: Paciente) => {
    setBajaError(null);
    try {
      await desactivarPaciente.mutateAsync(paciente.id);
      setAlertNotice('Paciente desactivado correctamente.');
      programarOcultarAviso();
    } catch {
      setBajaError(MENSAJE_ERROR_BAJA);
    }
  };

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

        {puedeGestionar && (
          <Button
            variant="primary"
            size="sm"
            onClick={openCreateModal}
            leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
          >
            Nuevo Paciente
          </Button>
        )}
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

      <PacienteTable
        pacientes={pacientes}
        isLoading={directorio.isFetching}
        loadError={loadError}
        onRetry={() => {
          if (rutaColeccion) void directorio.refetch();
        }}
        puedeGestionar={puedeGestionar}
        onEdit={openEditModal}
        onReactivate={handleReactivar}
        onDeactivate={handleDesactivar}
      />

      {/* ─────────────────────────────────────────
          MODAL DE PACIENTE (CREAR / EDITAR FICHA NOM-004)
          ───────────────────────────────────────── */}
      {isModalOpen && (
        <PacienteForm
          key={formKey}
          onClose={() => setIsModalOpen(false)}
          editingPaciente={editingPaciente}
          onSubmit={handleFormSubmit}
        />
      )}
    </div>
  );
}
