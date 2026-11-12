import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
import {
  Button,
  Input,
  Badge,
  Modal,
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
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import { useAuthStore } from '@/store/auth.store';
import {
  Building2,
  Plus,
  Search,
  Phone,
  MapPin,
  Pencil,
  Trash2,
  Loader2,
  RefreshCw,
} from 'lucide-react';

/** Mensaje único del estado de error de carga: el detalle técnico solo va a la consola. */
const MENSAJE_ERROR_CARGA =
  'No fue posible obtener el directorio de consultorios. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_BAJA =
  'No fue posible desactivar el consultorio. Revisa la conexión con el servicio e inténtalo de nuevo.';

const EMPTY_FORM = {
  nombre: '',
  direccion: '',
  telefono: '',
};

export function ConsultoriosPage() {
  /**
   * Organización dueña del directorio: sale del claim `tenantId` de la sesión, que es la autoridad
   * del tenant, y **nunca** se rellena con un valor por defecto (plan sección 2, guardarraíl 4).
   */
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  // Alta, edición y baja son de ADMIN en el backend (ConsultorioController); MEDICO solo lee. La
  // UI oculta las acciones de escritura para no ofrecer un botón que el backend va a rechazar.
  const puedeEditar = useAuthStore((state) => state.user?.role === 'ADMIN');

  /**
   * La colección cuelga de la organización dueña: `GET/POST /organizaciones/{id}/consultorios`. El
   * ítem es plano (`PUT/DELETE /consultorios/{id}`), así que solo la colección necesita la
   * organización. Sin claim no hay URL que pedir.
   */
  const rutaColeccion = organizacionId ? `/organizaciones/${organizacionId}/consultorios` : null;

  const [consultorios, setConsultorios] = useState<Consultorio[]>([]);
  const [isLoading, setIsLoading] = useState(() => rutaColeccion !== null);
  const [loadError, setLoadError] = useState<string | null>(() =>
    rutaColeccion ? null : MENSAJE_ERROR_CARGA,
  );
  const [search, setSearch] = useState('');

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [isDeactivating, setIsDeactivating] = useState(false);

  // `useCallback` porque el efecto de carga depende de esta función: si se recreara en cada
  // render, el efecto se dispararía en bucle.
  const loadData = useCallback((ruta: string) => {
    return api
      .get<Consultorio[]>(ruta)
      .then((res) => {
        setConsultorios(res.data);
        setLoadError(null);
      })
      .catch((err) => {
        // Se registra un resumen sin secretos (API_ADR-011) y además se hace visible el fallo:
        // un directorio vacío por error no puede confundirse con «no hay consultorios».
        console.error('Error al cargar consultorios:', resumenDeErrorHttp(err));
        setConsultorios([]);
        setLoadError(MENSAJE_ERROR_CARGA);
      })
      .finally(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    // Solo se pide lo que existe: sin organización no hay colección y el estado inicial ya lo dice.
    if (rutaColeccion) loadData(rutaColeccion);
  }, [rutaColeccion, loadData]);

  /**
   * Los avisos de éxito se ocultan solos. El temporizador se guarda en una referencia para poder
   * cancelarlo: uno pendiente intentaría escribir el estado de un componente ya desmontado.
   */
  const avisoRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const programarOcultarAviso = () => {
    if (avisoRef.current) clearTimeout(avisoRef.current);
    avisoRef.current = setTimeout(() => setAlertNotice(null), 3500);
  };
  useEffect(
    () => () => {
      if (avisoRef.current) clearTimeout(avisoRef.current);
    },
    [],
  );

  // Refresco manual: el loader y la limpieza del error se marcan aquí y no dentro de `loadData`,
  // para que el efecto inicial no encienda estados antes de que responda la petición.
  const handleRetry = () => {
    if (!rutaColeccion) return;
    setIsLoading(true);
    setLoadError(null);
    loadData(rutaColeccion);
  };

  const openCreateModal = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setSaveError(null);
    setIsModalOpen(true);
  };

  const openEditModal = (consultorio: Consultorio) => {
    setEditingId(consultorio.id);
    setForm({
      nombre: consultorio.nombre,
      direccion: consultorio.direccion || '',
      telefono: consultorio.telefono || '',
    });
    setSaveError(null);
    setIsModalOpen(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.nombre.trim()) return;

    const payload = {
      nombre: form.nombre,
      direccion: form.direccion || null,
      telefono: form.telefono || null,
    };

    try {
      setIsSubmitting(true);
      setSaveError(null);
      if (editingId) {
        // El ítem es plano: la organización dueña no se envía ni se puede cambiar.
        const res = await api.put<Consultorio>(`/consultorios/${editingId}`, payload);
        setConsultorios((prev) => prev.map((c) => (c.id === editingId ? res.data : c)));
        setAlertNotice('Consultorio actualizado correctamente.');
      } else {
        if (!rutaColeccion) {
          // Sin organización de la sesión no hay colección donde registrar y no se inventa una.
          return;
        }
        const res = await api.post<Consultorio>(rutaColeccion, payload);
        setConsultorios((prev) => [res.data, ...prev]);
        setAlertNotice('Consultorio registrado correctamente.');
      }
      setIsModalOpen(false);
      programarOcultarAviso();
    } catch (err) {
      console.error('Error al guardar consultorio:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSubmitting(false);
    }
  };

  // No hay botón "Activar": el backend no expone reactivación (ConsultorioController solo tiene
  // crear/listar/buscar/actualizar/desactivar, y Consultorio.actualizarDatos() documenta
  // explícitamente que el PUT nunca toca `activo`). Una vez desactivado, un consultorio queda
  // inactivo hasta que el backend agregue ese endpoint — no es algo que el frontend pueda resolver
  // por su cuenta. Brecha señalada en la HU3-5, pendiente de decisión del equipo de backend.
  const handleDeactivate = async () => {
    if (!deactivatingId) return;
    try {
      setIsDeactivating(true);
      await api.delete(`/consultorios/${deactivatingId}`);
      // Baja lógica: el backend no borra la fila (hay citas con llave foránea), solo apaga `activo`.
      setConsultorios((prev) =>
        prev.map((c) => (c.id === deactivatingId ? { ...c, activo: false } : c)),
      );
      setAlertNotice('Consultorio desactivado correctamente.');
      programarOcultarAviso();
    } catch (err) {
      console.error('Error al desactivar consultorio:', resumenDeErrorHttp(err));
      setAlertNotice(null);
      setSaveError(MENSAJE_ERROR_BAJA);
    } finally {
      setIsDeactivating(false);
      setDeactivatingId(null);
    }
  };

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

  const consultorioADesactivar = consultorios.find((c) => c.id === deactivatingId);

  return (
    <div className="space-y-6">
      {/* ─────────────────────────────────────────
          CABECERA DE PÁGINA
          ───────────────────────────────────────── */}
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
      {saveError && (
        <Alert variant="error" title="Error">
          {saveError}
        </Alert>
      )}

      {/* ─────────────────────────────────────────
          MÉTRICAS DEL DIRECTORIO
          ───────────────────────────────────────── */}
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

      {/* ─────────────────────────────────────────
          BÚSQUEDA
          ───────────────────────────────────────── */}
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

      {/* ─────────────────────────────────────────
          TABLA DE CONSULTORIOS
          ───────────────────────────────────────── */}
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
                        onClick={handleRetry}
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
                          onClick={() => openEditModal(c)}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                          title="Editar consultorio"
                        >
                          <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                          <span className="hidden sm:inline">Editar</span>
                        </button>
                        {c.activo && (
                          <button
                            type="button"
                            onClick={() => setDeactivatingId(c.id)}
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

      {/* ─────────────────────────────────────────
          MODAL DE CONSULTORIO (CREAR / EDITAR)
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingId ? 'Editar Consultorio' : 'Registrar Nuevo Consultorio'}
        description="Datos del espacio físico de la clínica."
        maxWidth="md"
      >
        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Nombre"
            value={form.nombre}
            onChange={(e) => setForm((f) => ({ ...f, nombre: e.target.value }))}
            required
            maxLength={120}
          />
          <Input
            label="Dirección"
            value={form.direccion}
            onChange={(e) => setForm((f) => ({ ...f, direccion: e.target.value }))}
            leftIcon={<MapPin className="w-4 h-4" />}
          />
          <Input
            label="Teléfono"
            value={form.telefono}
            onChange={(e) => setForm((f) => ({ ...f, telefono: e.target.value }))}
            leftIcon={<Phone className="w-4 h-4" />}
            maxLength={20}
          />

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsModalOpen(false)}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
            >
              {editingId ? 'Guardar Cambios' : 'Registrar Consultorio'}
            </Button>
          </div>
        </form>
      </Modal>

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
          ¿Desactivar <strong>{consultorioADesactivar?.nombre}</strong>? El sistema todavía no
          ofrece una forma de reactivarlo: confírmalo solo si estás seguro.
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
