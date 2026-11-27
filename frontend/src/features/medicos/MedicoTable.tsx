import { useMemo, useState } from 'react';
import {
  Input,
  Badge,
  Card,
  CardContent,
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
  Alert,
  Button,
} from '@/components/ui';
import {
  Stethoscope,
  Search,
  Phone,
  IdCard,
  Pencil,
  Trash2,
  UserCheck,
  Loader2,
  RefreshCw,
  CalendarClock,
} from 'lucide-react';
import type { Medico } from './medicos.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';

export interface MedicoTableProps {
  medicos: Medico[];
  /** Consultorios de la organización (incluye bajas, S12): nombra el de cada fila aunque esté
   *  dado de baja, en vez de vaciar la celda. */
  consultorios: Consultorio[];
  isLoading: boolean;
  loadError: string | null;
  onRetry: () => void;
  puedeEditarMedicos: boolean;
  /** Si la organización no tiene ningún consultorio activo, un médico nuevo no tiene a dónde
   *  asignarse: cambia el texto del directorio vacío para decir qué hacer primero. */
  sinConsultoriosDisponibles: boolean;
  onEdit: (medico: Medico) => void;
  onReactivate: (medico: Medico) => void;
  onDeactivateRequest: (id: string) => void;
  /** El botón «Horarios» depende del rol y de si la fila está activa (S4, S10). */
  puedeAbrirAgendaDe: (medico: Medico) => boolean;
  onOpenHorarios: (id: string) => void;
}

/** Directorio de médicos: métricas, buscador y tabla con sus acciones por fila. */
export function MedicoTable({
  medicos,
  consultorios,
  isLoading,
  loadError,
  onRetry,
  puedeEditarMedicos,
  sinConsultoriosDisponibles,
  onEdit,
  onReactivate,
  onDeactivateRequest,
  puedeAbrirAgendaDe,
  onOpenHorarios,
}: MedicoTableProps) {
  const [search, setSearch] = useState('');

  const consultorioPorId = (id: string) => consultorios.find((c) => c.id === id);

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

  return (
    <div className="space-y-6">
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
                        onClick={onRetry}
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
                      {puedeAbrirAgendaDe(m) && (
                        <button
                          type="button"
                          onClick={() => onOpenHorarios(m.id)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#CBD5E1] text-[#0A4D68] hover:bg-[#EFF4FF] text-xs font-medium transition-all"
                          title="Ver horarios de disponibilidad"
                        >
                          <CalendarClock className="w-3.5 h-3.5" />
                          <span className="hidden sm:inline">Horarios</span>
                        </button>
                      )}
                      {puedeEditarMedicos && (
                        <>
                          <button
                            type="button"
                            onClick={() => onEdit(m)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                            title="Editar médico"
                          >
                            <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                            <span className="hidden sm:inline">Editar</span>
                          </button>
                          {!m.activo && (
                            <button
                              type="button"
                              onClick={() => onReactivate(m)}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#059669] text-white hover:bg-[#047857] text-xs font-medium transition-all shadow-2xs"
                              title="Reactivar médico"
                            >
                              <UserCheck className="w-3.5 h-3.5 text-[#D1FAE5]" />
                              <span className="hidden sm:inline">Reactivar</span>
                            </button>
                          )}
                          {m.activo && (
                            <button
                              type="button"
                              onClick={() => onDeactivateRequest(m.id)}
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
                          {sinConsultoriosDisponibles
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
    </div>
  );
}
