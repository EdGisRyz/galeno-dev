import { useState, useEffect, useMemo } from 'react';
import { differenceInYears } from 'date-fns';
import { api } from '@/lib/axios';
import {
  Button,
  Input,
  Select,
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
import type { Paciente } from '@/features/pacientes/pacientes.types';
import type { PatientIdentification } from '@/types/futuro.types';
import {
  Users,
  UserPlus,
  Search,
  Phone,
  Mail,
  IdCard,
  Home,
  UserCheck,
  Pencil,
  Baby,
  FileText,
} from 'lucide-react';

type PacienteConFicha = Paciente & { ficha?: PatientIdentification };

const EMPTY_FORM = {
  nombre: '',
  telefono: '',
  fecha_nacimiento: '',
  sexo: '' as 'M' | 'F' | 'O' | '',
  email: '',
  notas: '',
  curp: '',
  ocupacion: '',
  domicilio_completo: '',
  nombre_responsable: '',
  telefono_responsable: '',
};

function calcularEdad(fechaNacimiento?: string | null): string {
  if (!fechaNacimiento) return 'Sin registrar';
  const years = differenceInYears(new Date(), new Date(fechaNacimiento));
  return years < 1 ? 'Menor de 1 año' : `${years} años`;
}

export function PacientesPage() {
  const [patients, setPatients] = useState<PacienteConFicha[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [search, setSearch] = useState('');

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  const loadData = () => {
    return api
      .get<PacienteConFicha[]>('/pacientes')
      .then((res) => setPatients(res.data))
      .catch((err) => console.error('Error al cargar pacientes:', err))
      .finally(() => setIsLoading(false));
  };

  useEffect(() => {
    loadData();
  }, []);

  const openCreateModal = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setIsModalOpen(true);
  };

  const openEditModal = (patient: PacienteConFicha) => {
    setEditingId(patient.id);
    setForm({
      nombre: patient.nombre,
      telefono: patient.telefono || '',
      fecha_nacimiento: patient.fecha_nacimiento || '',
      sexo: patient.sexo || '',
      email: patient.email || '',
      notas: patient.notas || '',
      curp: patient.ficha?.curp || '',
      ocupacion: patient.ficha?.ocupacion || '',
      domicilio_completo: patient.ficha?.domicilio_completo || '',
      nombre_responsable: patient.ficha?.nombre_responsable || '',
      telefono_responsable: patient.ficha?.telefono_responsable || '',
    });
    setIsModalOpen(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.nombre.trim()) return;

    const payload = {
      nombre: form.nombre,
      telefono: form.telefono || null,
      fecha_nacimiento: form.fecha_nacimiento || null,
      sexo: form.sexo || null,
      email: form.email || null,
      notas: form.notas || null,
      ficha: {
        curp: form.curp || null,
        ocupacion: form.ocupacion || null,
        domicilio_completo: form.domicilio_completo || null,
        nombre_responsable: form.nombre_responsable || null,
        telefono_responsable: form.telefono_responsable || null,
      },
    };

    try {
      setIsSubmitting(true);
      if (editingId) {
        const res = await api.put<PacienteConFicha>(`/pacientes/${editingId}`, payload);
        setPatients((prev) => prev.map((p) => (p.id === editingId ? res.data : p)));
        setAlertNotice('Ficha del paciente actualizada correctamente.');
      } else {
        const res = await api.post<PacienteConFicha>('/pacientes', payload);
        setPatients((prev) => [res.data, ...prev]);
        setAlertNotice('Paciente registrado correctamente.');
      }
      setIsModalOpen(false);
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar paciente:', err);
    } finally {
      setIsSubmitting(false);
    }
  };

  const filteredPatients = useMemo(() => {
    const term = search.toLowerCase();
    return patients.filter(
      (p) =>
        !term ||
        p.nombre.toLowerCase().includes(term) ||
        (p.telefono || '').includes(term) ||
        (p.ficha?.curp || '').toLowerCase().includes(term)
    );
  }, [patients, search]);

  const stats = useMemo(() => {
    const total = patients.length;
    const activos = patients.filter((p) => p.activo).length;
    const pediatricos = patients.filter(
      (p) => p.fecha_nacimiento && differenceInYears(new Date(), new Date(p.fecha_nacimiento)) < 18
    ).length;
    const conNotas = patients.filter((p) => p.notas).length;
    return { total, activos, pediatricos, conNotas };
  }, [patients]);

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

        <Button
          variant="primary"
          size="sm"
          onClick={openCreateModal}
          leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
        >
          Nuevo Paciente
        </Button>
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}

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
                {stats.total}
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
                {stats.activos}
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
                {stats.pediatricos}
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
                {stats.conNotas}
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
            placeholder="Buscar por nombre, teléfono o CURP..."
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
        <div className="flex items-center justify-between text-xs text-[#64748B] px-1">
          <span>
            Mostrando <strong>{filteredPatients.length}</strong> pacientes registrados
          </span>
        </div>

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Paciente</TableHead>
              <TableHead>Edad / Nacimiento</TableHead>
              <TableHead>Contacto</TableHead>
              <TableHead>CURP</TableHead>
              <TableHead>Responsable</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {!isLoading && filteredPatients.length > 0 ? (
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
                    <span className="text-xs text-[#41484D] block">{calcularEdad(p.fecha_nacimiento)}</span>
                    <span className="text-[11px] text-[#94A3B8] block mt-0.5">
                      {p.fecha_nacimiento || 'Sin fecha'}
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
                      {p.ficha?.curp || '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    <span className="text-xs text-[#41484D] block">
                      {p.ficha?.nombre_responsable || '—'}
                    </span>
                  </TableCell>

                  <TableCell>
                    <Badge variant={p.activo ? 'success' : 'default'} showDot>
                      {p.activo ? 'Activo' : 'Inactivo'}
                    </Badge>
                  </TableCell>

                  <TableCell className="text-right">
                    <button
                      type="button"
                      onClick={() => openEditModal(p)}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                      title="Editar ficha del paciente"
                    >
                      <Pencil className="w-3.5 h-3.5 text-[#88BDDC]" />
                      <span className="hidden sm:inline">Ficha</span>
                    </button>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} className="text-center py-12 text-[#94A3B8]">
                  {isLoading ? 'Cargando pacientes...' : 'No se encontraron pacientes con ese criterio.'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* ─────────────────────────────────────────
          MODAL DE PACIENTE (CREAR / EDITAR FICHA NOM-004)
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={editingId ? 'Editar Ficha del Paciente' : 'Registrar Nuevo Paciente'}
        description="Datos básicos y ficha de identificación según NOM-004."
        maxWidth="lg"
      >
        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
              Datos Básicos
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Nombre Completo"
                value={form.nombre}
                onChange={(e) => setForm((f) => ({ ...f, nombre: e.target.value }))}
                required
              />
              <Select
                label="Sexo"
                value={form.sexo}
                onChange={(e) => setForm((f) => ({ ...f, sexo: e.target.value as 'M' | 'F' | 'O' }))}
                options={[
                  { value: 'M', label: 'Masculino' },
                  { value: 'F', label: 'Femenino' },
                  { value: 'O', label: 'Otro' },
                ]}
                placeholder="Selecciona..."
              />
              <Input
                label="Teléfono"
                value={form.telefono}
                onChange={(e) => setForm((f) => ({ ...f, telefono: e.target.value }))}
                leftIcon={<Phone className="w-4 h-4" />}
              />
              <Input
                label="Fecha de Nacimiento"
                type="date"
                value={form.fecha_nacimiento}
                onChange={(e) => setForm((f) => ({ ...f, fecha_nacimiento: e.target.value }))}
              />
              <Input
                label="Correo Electrónico"
                type="email"
                value={form.email}
                onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                leftIcon={<Mail className="w-4 h-4" />}
                className="sm:col-span-2"
              />
            </div>
            <div className="mt-4">
              <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
                Notas Clínicas Generales
              </label>
              <textarea
                rows={2}
                className="block w-full rounded-lg border border-[#CBD5E1] bg-white p-3 text-sm text-[#0B1C30] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15"
                placeholder="Ej. Alergias, padecimientos crónicos..."
                value={form.notas}
                onChange={(e) => setForm((f) => ({ ...f, notas: e.target.value }))}
              />
            </div>
          </div>

          <div className="pt-4 border-t border-[#F1F5F9]">
            <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
              Ficha de Identificación (NOM-004)
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="CURP"
                value={form.curp}
                onChange={(e) => setForm((f) => ({ ...f, curp: e.target.value.toUpperCase() }))}
                leftIcon={<IdCard className="w-4 h-4" />}
                maxLength={18}
              />
              <Input
                label="Ocupación"
                value={form.ocupacion}
                onChange={(e) => setForm((f) => ({ ...f, ocupacion: e.target.value }))}
              />
              <Input
                label="Domicilio Completo"
                value={form.domicilio_completo}
                onChange={(e) => setForm((f) => ({ ...f, domicilio_completo: e.target.value }))}
                leftIcon={<Home className="w-4 h-4" />}
                className="sm:col-span-2"
              />
              <Input
                label="Nombre del Responsable"
                value={form.nombre_responsable}
                onChange={(e) => setForm((f) => ({ ...f, nombre_responsable: e.target.value }))}
                helperText="Para menores de edad o urgencias"
              />
              <Input
                label="Teléfono del Responsable"
                value={form.telefono_responsable}
                onChange={(e) => setForm((f) => ({ ...f, telefono_responsable: e.target.value }))}
              />
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsModalOpen(false)}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              leftIcon={<UserPlus className="w-4 h-4 text-[#88BDDC]" />}
            >
              {editingId ? 'Guardar Cambios' : 'Registrar Paciente'}
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
