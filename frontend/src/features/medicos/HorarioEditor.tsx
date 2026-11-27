import { useAuthStore } from '@/store/auth.store';
import type { Medico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useConsultoriosAsignados } from './medicos.queries';
import { ConsultoriosAsignadosPanel } from './ConsultoriosAsignadosPanel';
import { BloquesDeHorario } from './BloquesDeHorario';

export interface HorarioEditorProps {
  medico: Medico;
  /** Consultorios de la organización (incluye bajas, S12): de ahí sale lo que falta por asignar. */
  consultoriosOrganizacion: Consultorio[];
}

/**
 * Contenido del panel de horarios de un médico: consultorios asignados (S4, S6-S9) y bloques de
 * disponibilidad semanal (S4, S10, S11, S13), repartidos en `ConsultoriosAsignadosPanel` y
 * `BloquesDeHorario`. Este componente solo resuelve los permisos del rol y la única lectura que
 * comparten los dos: el conjunto de consultorios asignados.
 *
 * Vive montado solo mientras el panel está abierto —el `Modal` que lo envuelve desmonta sus hijos
 * al cerrarse (ver `Modal.tsx`)—, así que cada apertura es una instancia nueva: no hace falta
 * reiniciar el estado a mano al cambiar de médico.
 */
export function HorarioEditor({ medico, consultoriosOrganizacion }: HorarioEditorProps) {
  const rolDeSesion = useAuthStore((state) => state.user?.role);
  const medicoIdDeSesion = useAuthStore((state) => state.user?.medicoId ?? null);

  // Los horarios sí son del médico: el backend permite a MEDICO gestionar **su propia** agenda y
  // responde 403 con la de otro.
  const puedeGestionarHorarios =
    rolDeSesion === 'ADMIN' || (rolDeSesion === 'MEDICO' && medicoIdDeSesion === medico.id);
  // La lectura de los asignados la admite también el propio médico; asignar y quitar son de ADMIN.
  const puedeConsultarAsignados =
    rolDeSesion === 'ADMIN' || (rolDeSesion === 'MEDICO' && medicoIdDeSesion === medico.id);
  const puedeAsignarConsultorios = rolDeSesion === 'ADMIN';

  const asignadosQuery = useConsultoriosAsignados(medico.id, puedeConsultarAsignados);
  const consultoriosAsignados = asignadosQuery.data ?? [];

  return (
    <div className="space-y-4">
      {puedeConsultarAsignados && (
        <ConsultoriosAsignadosPanel
          medico={medico}
          consultoriosOrganizacion={consultoriosOrganizacion}
          consultoriosAsignados={consultoriosAsignados}
          isLoading={asignadosQuery.isLoading}
          isError={asignadosQuery.isError}
          puedeAsignarConsultorios={puedeAsignarConsultorios}
        />
      )}

      <BloquesDeHorario
        medico={medico}
        consultoriosAsignados={consultoriosAsignados}
        puedeGestionarHorarios={puedeGestionarHorarios}
      />
    </div>
  );
}
