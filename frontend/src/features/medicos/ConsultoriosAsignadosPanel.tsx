import { useMemo, useState } from 'react';
import { Button, Select, Badge, Alert } from '@/components/ui';
import { Trash2, Loader2, MapPin, Plus } from 'lucide-react';
import { mensajeDelBackend } from '@/lib/apiError';
import type { Medico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAsignarConsultorio, useQuitarConsultorio } from './medicos.queries';

const MENSAJE_ERROR_CONSULTORIOS_ASIGNADOS =
  'No fue posible obtener los consultorios asignados a este médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_ASIGNAR_CONSULTORIO =
  'No fue posible asignar el consultorio al médico. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_QUITAR_CONSULTORIO =
  'No fue posible quitar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';

export interface ConsultoriosAsignadosPanelProps {
  medico: Medico;
  /** Consultorios de la organización (incluye bajas, S12): de ahí sale lo que falta por asignar. */
  consultoriosOrganizacion: Consultorio[];
  /** Conjunto asignado al médico. Lo lee `HorarioEditor` (lo comparte con `BloquesDeHorario`, que
   *  también lo necesita) y aquí solo se muestra y se escribe. */
  consultoriosAsignados: Consultorio[];
  isLoading: boolean;
  isError: boolean;
  puedeAsignarConsultorios: boolean;
}

/**
 * Consultorios asignados a un médico: la lista, quitar uno y asignar uno nuevo (S4, S6-S9).
 *
 * La lectura la ve también el propio médico; asignar y quitar son solo de ADMIN, igual que en el
 * backend. El `POST`/`DELETE` de las dos escrituras responde 204 sin cuerpo, así que la forma
 * canónica —ordenada por el backend— sale de releer el listado (`useAsignarConsultorio` y
 * `useQuitarConsultorio` invalidan la consulta que vive en `HorarioEditor`).
 */
export function ConsultoriosAsignadosPanel({
  medico,
  consultoriosOrganizacion,
  consultoriosAsignados,
  isLoading,
  isError,
  puedeAsignarConsultorios,
}: ConsultoriosAsignadosPanelProps) {
  const asignarConsultorio = useAsignarConsultorio(medico.id);
  const quitarConsultorio = useQuitarConsultorio(medico.id);

  const [consultorioAAgregar, setConsultorioAAgregar] = useState('');
  const [asignacionError, setAsignacionError] = useState<string | null>(null);

  // Solo los consultorios activos de la organización que todavía no están asignados pueden
  // ofrecerse: la lista de asignados es un conjunto, no un historial.
  const consultoriosPorAsignar = useMemo(
    () =>
      consultoriosOrganizacion.filter(
        (c) => c.activo && !consultoriosAsignados.some((a) => a.id === c.id),
      ),
    [consultoriosOrganizacion, consultoriosAsignados],
  );

  const handleAsignarConsultorio = async () => {
    if (!consultorioAAgregar) return;
    try {
      setAsignacionError(null);
      await asignarConsultorio.mutateAsync(consultorioAAgregar);
      setConsultorioAAgregar('');
    } catch {
      setAsignacionError(MENSAJE_ERROR_ASIGNAR_CONSULTORIO);
    }
  };

  const handleQuitarConsultorio = async (consultorioId: string) => {
    try {
      setAsignacionError(null);
      await quitarConsultorio.mutateAsync(consultorioId);
    } catch (err) {
      setAsignacionError(mensajeDelBackend(err, MENSAJE_ERROR_QUITAR_CONSULTORIO));
    }
  };

  return (
    <section className="space-y-2">
      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-[#0B1C30]">
          Consultorios del médico
        </h4>
        <p className="text-[11px] text-[#64748B] mt-1">
          Cada bloque se dicta en uno de estos consultorios. El principal no se puede quitar.
        </p>
      </div>

      {isError && (
        <Alert variant="error" title="Error">
          {MENSAJE_ERROR_CONSULTORIOS_ASIGNADOS}
        </Alert>
      )}
      {asignacionError && (
        <Alert variant="error" title="No se pudo completar la operación">
          {asignacionError}
        </Alert>
      )}

      {isLoading ? (
        <span
          role="status"
          aria-live="polite"
          className="inline-flex items-center gap-2 text-[#64748B] text-sm py-2"
        >
          <Loader2 className="w-4 h-4 animate-spin" />
          Cargando consultorios asignados...
        </span>
      ) : consultoriosAsignados.length > 0 ? (
        <ul aria-label="Consultorios asignados" className="space-y-1.5">
          {consultoriosAsignados.map((c) => (
            <li
              key={c.id}
              className="flex items-center justify-between px-3 py-2 rounded-lg border border-[#E2E8F0] bg-[#F8FAFC]"
            >
              <span className="text-xs text-[#41484D] flex items-center gap-2">
                <MapPin className="w-3.5 h-3.5 text-[#64748B]" />
                <span className="text-[#0B1C30]">{c.nombre}</span>
                {medico.consultorioId === c.id && (
                  <Badge variant="primary" showDot={false}>
                    Principal
                  </Badge>
                )}
              </span>
              {puedeAsignarConsultorios && (
                <button
                  type="button"
                  onClick={() => handleQuitarConsultorio(c.id)}
                  disabled={quitarConsultorio.isPending}
                  aria-label={`Quitar ${c.nombre}`}
                  title="Quitar consultorio"
                  className="p-1.5 rounded-md text-[#E11D48] hover:bg-[#FEF2F2] disabled:opacity-50"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-[#94A3B8] py-2">
          Este médico todavía no tiene consultorios asignados.
        </p>
      )}

      {puedeAsignarConsultorios && consultoriosPorAsignar.length > 0 && (
        <div className="flex flex-col sm:flex-row sm:items-end gap-2">
          <Select
            label="Consultorio a asignar"
            value={consultorioAAgregar}
            onChange={(e) => setConsultorioAAgregar(e.target.value)}
            options={consultoriosPorAsignar.map((c) => ({
              value: c.id,
              label: c.nombre,
            }))}
            placeholder="Selecciona..."
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleAsignarConsultorio}
            isLoading={asignarConsultorio.isPending}
            disabled={!consultorioAAgregar}
            leftIcon={<Plus className="w-3.5 h-3.5" />}
          >
            Asignar consultorio
          </Button>
        </div>
      )}
    </section>
  );
}
