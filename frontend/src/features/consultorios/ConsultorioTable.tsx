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
  Building2,
  Search,
  Phone,
  MapPin,
  Pencil,
  Trash2,
  UserCheck,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import type { Consultorio } from './consultorios.types';

export interface ConsultorioTableProps {
  consultorios: Consultorio[];
  isLoading: boolean;
  loadError: string | null;
  onRetry: () => void;
  puedeEditar: boolean;
  onEdit: (consultorio: Consultorio) => void;
  onReactivate: (consultorio: Consultorio) => void;
  onDeactivateRequest: (id: string) => void;
}

/** Directorio de consultorios: métricas, buscador y tabla con sus acciones por fila. */
export function ConsultorioTable({
  consultorios,
  isLoading,
  loadError,
  onRetry,
  puedeEditar,
  onEdit,
  onReactivate,
  onDeactivateRequest,
}: ConsultorioTableProps) {
  const [search, setSearch] = useState('');

  const filteredConsultorios = useMemo(() => {
    const term = search.toLowerCase();
    return consultorios.filter(
      (c) =>
        !term ||
        c.nombre.toLowerCase().includes(term) ||
        (c.direccion || '').toLowerCase().includes(term),
    );
  }, [consultorios, search]);

  // El servicio solo devuelve consultorios activos, así que no existe un conteo de inactivos que
  // mostrar: las métricas se derivan de lo que sí llegó.
  const stats = useMemo(() => {
    const total = consultorios.length;
    const conDireccion = consultorios.filter((c) => (c.direccion || '').trim() !== '').length;
    const conTelefono = consultorios.filter((c) => (c.telefono || '').trim() !== '').length;
    return { total, conDireccion, conTelefono };
  }, [consultorios]);

  /**
   * Un dato que no se pudo leer no es cero: mientras carga o si la carga falló, la métrica se
   * muestra como desconocida. Escribir 0 afirmaría que el directorio está vacío.
   */
  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Consultorios
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <Building2 className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Con dirección
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.conDireccion)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center">
              <MapPin className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Con teléfono
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#41484D] mt-1 tnum">
                {metrica(stats.conTelefono)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#F1F5F9] text-[#64748B] flex items-center justify-center">
              <Phone className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre o dirección..."
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
              Mostrando <strong>{filteredConsultorios.length}</strong> consultorios registrados
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Consultorio</TableHead>
              <TableHead>Dirección</TableHead>
              <TableHead>Teléfono</TableHead>
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
                    Cargando consultorios...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar los consultorios">
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
            ) : filteredConsultorios.length > 0 ? (
              filteredConsultorios.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    <span className="font-semibold text-sm text-[#0B1C30] block">{c.nombre}</span>
                  </TableCell>

                  <TableCell>
                    {c.direccion ? (
                      <span className="text-xs text-[#41484D] flex items-center gap-1.5">
                        <MapPin className="w-3 h-3 text-[#64748B]" /> {c.direccion}
                      </span>
                    ) : (
                      <span className="text-xs text-[#94A3B8]">—</span>
                    )}
                  </TableCell>

                  <TableCell>
                    {c.telefono ? (
                      <span className="text-xs text-[#41484D] flex items-center gap-1.5">
                        <Phone className="w-3 h-3 text-[#64748B]" /> {c.telefono}
                      </span>
                    ) : (
                      <span className="text-xs text-[#94A3B8]">—</span>
                    )}
                  </TableCell>

                  <TableCell>
                    <Badge variant={c.activo ? 'success' : 'default'} showDot>
                      {c.activo ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>

                  {puedeEditar && (
                    <TableCell className="text-right">
                      <div className="inline-flex items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => onEdit(c)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                          title="Editar consultorio"
                        >
                          <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                          <span className="hidden sm:inline">Editar</span>
                        </button>
                        {!c.activo && (
                          <button
                            type="button"
                            onClick={() => onReactivate(c)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#059669] text-white hover:bg-[#047857] text-xs font-medium transition-all shadow-2xs"
                            title="Reactivar consultorio"
                          >
                            <UserCheck className="w-3.5 h-3.5 text-[#D1FAE5]" />
                            <span className="hidden sm:inline">Reactivar</span>
                          </button>
                        )}
                        {c.activo && (
                          <button
                            type="button"
                            onClick={() => onDeactivateRequest(c.id)}
                            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                            title="Desactivar consultorio"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                            <span className="hidden sm:inline">Desactivar</span>
                          </button>
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
                      No se encontraron consultorios con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <Building2 className="w-4 h-4 text-[#64748B]" />
                        Aún no hay consultorios registrados
                      </p>
                      {puedeEditar && (
                        <p className="text-[#94A3B8]">
                          Da de alta el primer consultorio con el botón «Nuevo Consultorio».
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
