import { useState } from 'react';
import { Button, Input, Select, Modal, Alert } from '@/components/ui';
import { Pencil, Trash2, Loader2, Clock, MapPin } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import type { Medico, HorarioMedico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useHorarios, useCrearHorario, useActualizarHorario, useEliminarHorario } from './medicos.queries';

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

function formularioVacio(consultorioId: string) {
  return {
    diaSemana: '1',
    horaInicio: '09:00',
    horaFin: '14:00',
    duracionConsulta: '30',
    consultorioId,
  };
}

type FormularioHorario = ReturnType<typeof formularioVacio>;

export interface BloquesDeHorarioProps {
  medico: Medico;
  /** Consultorios asignados al médico (de `HorarioEditor`, compartido con el panel de
   *  asignados): de ahí sale lo que puede elegir un bloque (S13). */
  consultoriosAsignados: Consultorio[];
  puedeGestionarHorarios: boolean;
}

/**
 * Bloques de disponibilidad semanal de un médico: la lista, el alta/edición y el borrado
 * (S4, S10, S11, S13 del pedido de validación).
 */
export function BloquesDeHorario({
  medico,
  consultoriosAsignados,
  puedeGestionarHorarios,
}: BloquesDeHorarioProps) {
  const horariosQuery = useHorarios(medico.id);
  const crearHorario = useCrearHorario(medico.id);
  const actualizarHorario = useActualizarHorario(medico.id);
  const eliminarHorario = useEliminarHorario(medico.id);

  const horarios = horariosQuery.data ?? [];

  const [horarioForm, setHorarioForm] = useState<FormularioHorario>(() =>
    formularioVacio(medico.consultorioId),
  );
  const [editingHorarioId, setEditingHorarioId] = useState<string | null>(null);
  const [horarioFormError, setHorarioFormError] = useState<string | null>(null);
  const [horarioAEliminar, setHorarioAEliminar] = useState<string | null>(null);
  // Error del borrado de un bloque: ocupa el mismo aviso que el de carga de la agenda (más abajo),
  // nunca los dos a la vez. El de carga se deriva directamente de la consulta, sin estado propio.
  const [horarioEliminarError, setHorarioEliminarError] = useState<string | null>(null);

  const horariosError = horarioEliminarError
    ? horarioEliminarError
    : horariosQuery.isError
      ? mensajeDelBackend(horariosQuery.error, MENSAJE_ERROR_HORARIOS)
      : null;

  // Si el consultorio elegido en el formulario dejó de estar asignado —lo acaba de quitar el
  // administrador—, el bloque no puede seguir apuntando a él: vuelve al principal. Ajustado durante
  // el render (no en un efecto) en cuanto cambia la lista de asignados que trajo el servidor.
  const [asignadosProcesados, setAsignadosProcesados] = useState(consultoriosAsignados);
  if (consultoriosAsignados !== asignadosProcesados) {
    setAsignadosProcesados(consultoriosAsignados);
    setHorarioForm((f) => {
      if (consultoriosAsignados.some((c) => c.id === f.consultorioId)) return f;
      const principal = consultoriosAsignados.find((c) => c.id === medico.consultorioId)?.id;
      return { ...f, consultorioId: principal ?? consultoriosAsignados[0]?.id ?? '' };
    });
  }

  // Selector del bloque (S13): solo los consultorios asignados que están activos, porque el
  // backend rechaza con 409 un bloque en un consultorio de baja. La excepción es la edición: el
  // consultorio actual del bloque se muestra aunque esté inactivo — corregir la hora no es
  // moverlo, y sin esa opción el campo quedaría con un valor fuera de la lista.
  const consultoriosActivosAsignados = consultoriosAsignados.filter((c) => c.activo);
  const consultorioActualDelBloque = consultoriosAsignados.find(
    (c) => c.id === horarioForm.consultorioId,
  );
  const consultoriosDelBloque =
    editingHorarioId && consultorioActualDelBloque && !consultorioActualDelBloque.activo
      ? [...consultoriosActivosAsignados, consultorioActualDelBloque]
      : consultoriosActivosAsignados;

  const openEditHorario = (h: HorarioMedico) => {
    setEditingHorarioId(h.id);
    setHorarioForm({
      diaSemana: String(h.diaSemana),
      horaInicio: h.horaInicio.slice(0, 5),
      horaFin: h.horaFin.slice(0, 5),
      duracionConsulta: String(h.duracionConsulta),
      consultorioId: h.consultorioId,
    });
    setHorarioFormError(null);
  };

  const handleHorarioSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const bloqueOriginal = horarios.find((h) => h.id === editingHorarioId);
    const conservaElConsultorioDelBloque =
      bloqueOriginal !== undefined && bloqueOriginal.consultorioId === horarioForm.consultorioId;

    const payload = {
      diaSemana: Number(horarioForm.diaSemana),
      horaInicio: `${horarioForm.horaInicio}:00`,
      horaFin: `${horarioForm.horaFin}:00`,
      duracionConsulta: Number(horarioForm.duracionConsulta),
      // Opcional en el backend: sin él el alta usa el principal y la edición conserva el actual.
      // Al editar se omite cuando no cambió: reenviar el mismo consultorio cuenta como mover el
      // bloque y, si está dado de baja, el backend responde 409 (S11). Corregir la hora no es
      // moverlo (S13).
      ...(horarioForm.consultorioId && !conservaElConsultorioDelBloque
        ? { consultorioId: horarioForm.consultorioId }
        : {}),
    };

    try {
      setHorarioFormError(null);
      if (editingHorarioId) {
        await actualizarHorario.mutateAsync({ id: editingHorarioId, payload });
      } else {
        await crearHorario.mutateAsync(payload);
      }
      setHorarioForm(formularioVacio(medico.consultorioId));
      setEditingHorarioId(null);
    } catch (err) {
      // El rechazo de un bloque tiene causas propias —rango invertido, solapamiento con otro
      // bloque del mismo día— y el backend las explica en su mensaje.
      setHorarioFormError(mensajeDelBackend(err, MENSAJE_ERROR_HORARIO_GUARDADO));
    }
  };

  const handleDeleteHorario = async () => {
    if (!horarioAEliminar) return;
    try {
      await eliminarHorario.mutateAsync(horarioAEliminar);
    } catch (err) {
      setHorarioEliminarError(mensajeDelBackend(err, MENSAJE_ERROR_HORARIO_BAJA));
    } finally {
      setHorarioAEliminar(null);
    }
  };

  const horarioAEliminarDatos = horarios.find((h) => h.id === horarioAEliminar);

  return (
    <>
      {horariosError && (
        <Alert variant="error" title="Error">
          {horariosError}
        </Alert>
      )}

      {horariosQuery.isLoading ? (
        <span
          role="status"
          aria-live="polite"
          className="inline-flex items-center gap-2 text-[#64748B] text-sm py-4"
        >
          <Loader2 className="w-4 h-4 animate-spin" />
          Cargando horarios...
        </span>
      ) : horarios.length > 0 ? (
        <ul aria-label="Bloques de disponibilidad" className="space-y-1.5">
          {horarios.map((h) => (
            <li
              key={h.id}
              className="flex items-center justify-between px-3 py-2 rounded-lg border border-[#E2E8F0] bg-[#F8FAFC]"
            >
              <span className="text-xs text-[#41484D] flex items-center gap-2 flex-wrap">
                <Clock className="w-3.5 h-3.5 text-[#64748B]" />
                <strong className="text-[#0B1C30]">
                  {DIAS_SEMANA.find((d) => d.value === h.diaSemana)?.label}
                </strong>
                {h.horaInicio.slice(0, 5)}–{h.horaFin.slice(0, 5)} · citas de {h.duracionConsulta}{' '}
                min
                <span className="text-[#0A4D68] flex items-center gap-1.5">
                  <MapPin className="w-3 h-3" />
                  {h.consultorioNombre}
                </span>
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
            label="Consultorio del bloque"
            value={horarioForm.consultorioId}
            onChange={(e) => setHorarioForm((f) => ({ ...f, consultorioId: e.target.value }))}
            options={consultoriosDelBloque.map((c) => ({ value: c.id, label: c.nombre }))}
            disabled={consultoriosDelBloque.length === 0}
          />
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
            onChange={(e) => setHorarioForm((f) => ({ ...f, duracionConsulta: e.target.value }))}
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
                  setHorarioForm(formularioVacio(medico.consultorioId));
                  setHorarioFormError(null);
                }}
              >
                Cancelar edición
              </Button>
            )}
            <Button
              type="submit"
              variant="primary"
              size="sm"
              isLoading={crearHorario.isPending || actualizarHorario.isPending}
            >
              {editingHorarioId ? 'Guardar bloque' : 'Agregar bloque'}
            </Button>
          </div>
        </form>
      )}

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
        // Se abre desde dentro del panel de horarios: sin la capa superior quedaría debajo del
        // panel y el aviso de confirmación no se vería.
        nivelSuperior
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
    </>
  );
}
