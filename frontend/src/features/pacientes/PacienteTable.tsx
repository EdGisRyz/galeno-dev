import { useMemo, useState } from 'react';
import { differenceInYears } from 'date-fns';
import {
  Input,
  Badge,
  Button,
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
import {
  Users,
  Search,
  Phone,
  Mail,
  UserCheck,
  UserX,
  Pencil,
  Baby,
  FileText,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import type { Paciente } from './pacientes.types';

function calcularEdad(fechaNacimiento?: string | null): string {
  if (!fechaNacimiento) return 'Sin registrar';
  const years = differenceInYears(new Date(), new Date(fechaNacimiento));
  return years < 1 ? 'Menor de 1 año' : `${years} años`;
}

export interface PacienteTableProps {
  pacientes: Paciente[];
  isLoading: boolean;
  loadError: string | null;
  onRetry: () => void;
  puedeGestionar: boolean;
  onEdit: (paciente: Paciente) => void;
  onReactivate: (paciente: Paciente) => void;
  onDeactivate: (paciente: Paciente) => void;
}

/** Directorio de pacientes: métricas, buscador y tabla con sus acciones por fila. */
export function PacienteTable({
  pacientes,
  isLoading,
  loadError,
  onRetry,
  puedeGestionar,
  onEdit,
  onReactivate,
  onDeactivate,
}: PacienteTableProps) {
  const [search, setSearch] = useState('');

  /**
   * Búsqueda del directorio: nombre, teléfono, CURP y ocupación. La CURP se compara en
   * minúsculas por los dos lados, así que da igual escribir la forma canónica o como la
   * dictaron al mostrador; el teléfono se compara tal cual porque solo tiene dígitos y guiones.
   */
  const filteredPatients = useMemo(() => {
    const term = search.toLowerCase();
    return pacientes.filter(
      (p) =>
        !term ||
        p.nombre.toLowerCase().includes(term) ||
        (p.telefono || '').includes(term) ||
        (p.curp || '').toLowerCase().includes(term) ||
        (p.ocupacion || '').toLowerCase().includes(term),
    );
  }, [pacientes, search]);

  const stats = useMemo(() => {
    const total = pacientes.length;
    const activos = pacientes.filter((p) => p.activo).length;
    const pediatricos = pacientes.filter(
      (p) => p.fechaNacimiento && differenceInYears(new Date(), new Date(p.fechaNacimiento)) < 18,
    ).length;
    const conNotas = pacientes.filter((p) => p.notas).length;
    return { total, activos, pediatricos, conNotas };
  }, [pacientes]);

  /**
   * Un dato que no se pudo leer no es cero: mientras carga o si la carga falló, la métrica se
   * muestra como desconocida. Escribir 0 afirmaría que el directorio está vacío.
   */
  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  return (
    <div className="space-y-6">
      {/* ─────────────────────────────────────────
          MÉTRICAS DEL DIRECTORIO
          ───────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Pacientes
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <Users className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Activos
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.activos)}
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
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#4338CA]">
                Pediátricos
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#4338CA] mt-1 tnum">
                {metrica(stats.pediatricos)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EEF2FF] text-[#4F46E5] flex items-center justify-center">
              <Baby className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#B45309]">
                Con Notas Clínicas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#B45309] mt-1 tnum">
                {metrica(stats.conNotas)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#FFFBEB] text-[#D97706] flex items-center justify-center">
              <FileText className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ─────────────────────────────────────────
          BÚSQUEDA
          ───────────────────────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre, teléfono, CURP u ocupación..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            leftIcon={<Search className="w-4 h-4" />}
          />
        </CardContent>
      </Card>

      {/* ─────────────────────────────────────────
          TABLA DE PACIENTES
          ───────────────────────────────────────── */}
      <div className="space-y-3">
        {!isLoading && !loadError && (
          <div className="flex items-center justify-between text-xs text-[#64748B] px-1">
            <span>
              Mostrando <strong>{filteredPatients.length}</strong> pacientes registrados
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Paciente</TableHead>
              <TableHead>Edad / Nacimiento</TableHead>
              <TableHead>Contacto</TableHead>
              <TableHead>CURP</TableHead>
              <TableHead>Responsable</TableHead>
              <TableHead>Estado</TableHead>
              {puedeGestionar && <TableHead className="text-right">Acciones</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={puedeGestionar ? 7 : 6} className="text-center py-12">
                  <span
                    role="status"
                    aria-live="polite"
                    className="inline-flex items-center gap-2 text-[#64748B]"
                  >
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Cargando pacientes...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={puedeGestionar ? 7 : 6} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar los pacientes">
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
            ) : filteredPatients.length > 0 ? (
              filteredPatients.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>
                    <span className="font-semibold text-sm text-[#0B1C30] block">{p.nombre}</span>
                    {p.sexo && (
                      <span className="bg-[#EFF4FF] text-[#0A4D68] px-1.5 py-0.2 rounded text-[10px] font-bold mt-0.5 inline-block">
                        {p.sexo}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] block">{calcularEdad(p.fechaNacimiento)}</span>
                    <span className="text-[11px] text-[#94A3B8] block mt-0.5">
                      {p.fechaNacimiento || 'Sin fecha'}
                    </span>
                  </TableCell>

                  <TableCell>
                    {p.telefono && (
                      <span className="text-xs text-[#41484D] flex items-center gap-1.5">
                        <Phone className="w-3 h-3 text-[#64748B]" /> {p.telefono}
                      </span>
                    )}
                    {p.email && (
                      <span className="text-[11px] text-[#64748B] flex items-center gap-1.5 mt-0.5">
                        <Mail className="w-3 h-3" /> {p.email}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] font-mono">
                      {p.curp || '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] block">
                      {p.nombreResponsable || '—'}
                    </span>
                    {/* El parentesco se muestra junto al responsable: «quién es» y «qué es del
                        paciente» son el mismo dato para quien atiende el mostrador. */}
                    {p.parentesco && (
                      <span className="text-[11px] text-[#64748B] block mt-0.5">
                        {p.parentesco}
                      </span>
                    )}
                  </TableCell>

                  <TableCell>
                    <Badge variant={p.activo ? 'success' : 'default'} showDot>
                      {p.activo ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>

                  {puedeGestionar && (
                    <TableCell className="text-right">
                      <div className="inline-flex items-center justify-end gap-2">
                        {/* La baja es lógica, así que una fila inactiva se puede reactivar sin
                            duplicar al paciente. Mismo tamaño y forma que el resto de acciones de
                            la tabla, con el color positivo de los estados activos. */}
                        {!p.activo && (
                          <button
                            type="button"
                            onClick={() => onReactivate(p)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#059669] text-white hover:bg-[#047857] text-xs font-medium transition-all shadow-2xs"
                            title="Reactivar al paciente dado de baja"
                          >
                            <UserCheck className="w-3.5 h-3.5 text-[#D1FAE5]" />
                            <span className="hidden sm:inline">Reactivar</span>
                          </button>
                        )}
                        {p.activo && (
                          <button
                            type="button"
                            onClick={() => onDeactivate(p)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                            title="Dar de baja al paciente"
                          >
                            <UserX className="w-3.5 h-3.5" />
                            <span className="hidden sm:inline">Desactivar</span>
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => onEdit(p)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                          title="Editar ficha del paciente"
                        >
                          <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                          <span className="hidden sm:inline">Ficha</span>
                        </button>
                      </div>
                    </TableCell>
                  )}
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={puedeGestionar ? 7 : 6} className="text-center py-12">
                  {search ? (
                    <span className="text-[#94A3B8]">
                      No se encontraron pacientes con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <Users className="w-4 h-4 text-[#64748B]" />
                        Aún no hay pacientes registrados
                      </p>
                      <p className="text-[#94A3B8]">
                        Da de alta al primer paciente con el botón «Nuevo Paciente».
                      </p>
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
