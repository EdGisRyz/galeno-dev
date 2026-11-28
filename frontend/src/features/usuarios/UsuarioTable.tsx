import { useMemo, useState } from 'react';
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
  UserCog,
  UserCheck,
  UserX,
  Search,
  Pencil,
  Trash2,
  RotateCcw,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import { nombrePrincipalDeLaCuenta, tieneNombreDePersona } from './validacionUsuario';
import type { Usuario, RolUsuario } from './usuarios.types';
import type { Medico } from '@/features/medicos/medicos.types';

const VARIANTE_POR_ROL: Record<RolUsuario, 'primary' | 'secondary' | 'warning' | 'default'> = {
  ADMIN: 'primary',
  MEDICO: 'secondary',
  RECEPCIONISTA: 'warning',
  PACIENTE: 'default',
};

export interface UsuarioTableProps {
  usuarios: Usuario[];
  medicos: Medico[];
  isLoading: boolean;
  loadError: string | null;
  onRetry: () => void;
  puedeEditar: boolean;
  emailDeSesion: string | undefined;
  activatingId: string | null;
  onEdit: (usuario: Usuario) => void;
  onActivate: (id: string) => void;
  onDeactivateRequest: (id: string) => void;
}

/** Directorio de cuentas: métricas, buscador y tabla con sus acciones por fila. */
export function UsuarioTable({
  usuarios,
  medicos,
  isLoading,
  loadError,
  onRetry,
  puedeEditar,
  emailDeSesion,
  activatingId,
  onEdit,
  onActivate,
  onDeactivateRequest,
}: UsuarioTableProps) {
  const [search, setSearch] = useState('');

  const medicoPorId = (id: string | null | undefined) => medicos.find((m) => m.id === id);
  const esLaPropiaCuenta = (usuario: Usuario) => usuario.email.toLowerCase() === emailDeSesion;

  const filteredUsuarios = useMemo(() => {
    const term = search.toLowerCase();
    // S4: la lista muestra personas, así que la búsqueda tiene que encontrar por nombre además de
    // por correo; dejar solo el correo haría invisible a quien busca por el nombre que está viendo.
    return usuarios.filter(
      (u) =>
        !term ||
        u.email.toLowerCase().includes(term) ||
        (u.nombre ?? '').toLowerCase().includes(term),
    );
  }, [usuarios, search]);

  const stats = useMemo(() => {
    const total = usuarios.length;
    const activas = usuarios.filter((u) => u.activo).length;
    return { total, activas, inactivas: total - activas };
  }, [usuarios]);

  const metrica = (valor: number): string | number => (isLoading || loadError ? '—' : valor);

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Total Cuentas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {metrica(stats.total)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <UserCog className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Activas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {metrica(stats.activas)}
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
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Inactivas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#41484D] mt-1 tnum">
                {metrica(stats.inactivas)}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#F1F5F9] text-[#64748B] flex items-center justify-center">
              <UserX className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardContent className="p-4">
          <Input
            placeholder="Buscar por nombre o correo..."
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
              Mostrando <strong>{filteredUsuarios.length}</strong> cuentas registradas
            </span>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Persona</TableHead>
              <TableHead>Rol</TableHead>
              <TableHead>Médico asociado</TableHead>
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
                    Cargando cuentas...
                  </span>
                </TableCell>
              </TableRow>
            ) : loadError ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8">
                  <Alert variant="error" title="No se pudieron cargar las cuentas">
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
            ) : filteredUsuarios.length > 0 ? (
              filteredUsuarios.map((u) => (
                <TableRow key={u.id}>
                  <TableCell>
                    {/*
                      S4: el dato principal es la persona. Una cuenta anterior a la V10 no tiene
                      nombre y muestra el correo tal cual: no se deriva un nombre de la dirección.
                      El correo sigue visible como dato secundario cuando hay nombre, porque es con
                      lo que la persona inicia sesión.
                    */}
                    <span className="text-xs font-semibold text-[#0B1C30] block">
                      {nombrePrincipalDeLaCuenta(u)}
                    </span>
                    {tieneNombreDePersona(u) && (
                      <span className="text-[11px] text-[#64748B] block">{u.email}</span>
                    )}
                    {esLaPropiaCuenta(u) && (
                      <span className="text-[10px] text-[#64748B] block">Tu cuenta</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant={VARIANTE_POR_ROL[u.rol]} showDot={false}>
                      {u.rol}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <span className="text-xs text-[#41484D]">
                      {medicoPorId(u.medicoId)?.nombre || '—'}
                    </span>
                  </TableCell>
                  <TableCell>
                    <Badge variant={u.activo ? 'success' : 'default'} showDot>
                      {u.activo ? 'Activa' : 'Inactiva'}
                    </Badge>
                  </TableCell>
                  {puedeEditar && (
                    <TableCell className="text-right">
                      <div className="inline-flex items-center gap-1.5">
                        {u.activo ? (
                          <>
                            <button
                              type="button"
                              onClick={() => onEdit(u)}
                              className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                              title="Editar cuenta"
                            >
                              <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                              <span className="hidden sm:inline">Editar</span>
                            </button>
                            {!esLaPropiaCuenta(u) && (
                              <button
                                type="button"
                                onClick={() => onDeactivateRequest(u.id)}
                                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-[#FCA5A5] text-[#E11D48] hover:bg-[#FEF2F2] text-xs font-medium transition-all"
                                title="Dar de baja"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                                <span className="hidden sm:inline">Baja</span>
                              </button>
                            )}
                          </>
                        ) : (
                          <Button
                            variant="outline"
                            size="sm"
                            isLoading={activatingId === u.id}
                            onClick={() => onActivate(u.id)}
                            leftIcon={<RotateCcw className="w-3.5 h-3.5" />}
                          >
                            Reactivar
                          </Button>
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
                      No se encontraron cuentas con ese criterio.
                    </span>
                  ) : (
                    <div className="space-y-1">
                      <p className="flex items-center justify-center gap-2 text-[#41484D] font-semibold">
                        <UserCog className="w-4 h-4 text-[#64748B]" />
                        Aún no hay cuentas registradas
                      </p>
                      {puedeEditar && (
                        <p className="text-[#94A3B8]">
                          Da de alta la primera cuenta con el botón «Nueva Cuenta».
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
