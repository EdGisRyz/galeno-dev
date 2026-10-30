import { useState, useEffect, useMemo } from 'react';
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
import type { Cita, EstadoCita } from '@/features/citas/citas.types';
import type { Consultorio } from '@/features/consultorios/consultorios.types';
import type { Medico } from '@/features/medicos/medicos.types';
import type { Paciente } from '@/features/pacientes/pacientes.types';
import {
  Calendar as CalendarIcon,
  Clock,
  Plus,
  User,
  Filter,
  CheckCircle2,
  XCircle,
  FileText,
  RefreshCw,
} from 'lucide-react';

export function AgendaPage() {
  const [appointments, setAppointments] = useState<Cita[]>([]);
  const [doctors, setDoctors] = useState<Medico[]>([]);
  const [patients, setPatients] = useState<Paciente[]>([]);
  const [clinicRooms, setClinicRooms] = useState<Consultorio[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Filtros
  const [selectedDoctorId, setSelectedDoctorId] = useState<string>('all');
  const [selectedStatus, setSelectedStatus] = useState<string>('all');
  const [searchPatient, setSearchPatient] = useState<string>('');

  // Modal Nueva Cita
  const [isNewAptModalOpen, setIsNewAptModalOpen] = useState(false);
  const [newPatientId, setNewPatientId] = useState('');
  const [newDoctorId, setNewDoctorId] = useState('');
  const [newRoomId, setNewRoomId] = useState('');
  const [newDate, setNewDate] = useState(new Date().toISOString().split('T')[0]);
  const [newTime, setNewTime] = useState('10:00');
  const [newDuration, setNewDuration] = useState('30');
  const [newMotivo, setNewMotivo] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  // Cargar datos
  const loadData = () => {
    return Promise.all([
      api.get<Cita[]>('/citas'),
      api.get<Medico[]>('/medicos'),
      api.get<Paciente[]>('/pacientes'),
      api.get<Consultorio[]>('/consultorios'),
    ])
      .then(([aptRes, docRes, patRes, roomRes]) => {
        setAppointments(aptRes.data);
        setDoctors(docRes.data);
        setPatients(patRes.data);
        setClinicRooms(roomRes.data);

        if (docRes.data.length > 0) setNewDoctorId(docRes.data[0].id);
        if (patRes.data.length > 0) setNewPatientId(patRes.data[0].id);
        if (roomRes.data.length > 0) setNewRoomId(roomRes.data[0].id);
      })
      .catch((err) => {
        console.error('Error al cargar agenda médica:', err);
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  // Refresco manual (botón "Actualizar"): aquí sí mostramos el loader de inmediato
  const handleRefresh = () => {
    setIsLoading(true);
    loadData();
  };

  useEffect(() => {
    loadData();
  }, []);

  // Actualizar estado de cita (ej. Confirmar o Cancelar)
  const handleUpdateStatus = async (aptId: string, newStatus: EstadoCita) => {
    try {
      await api.patch(`/citas/${aptId}/estado`, { estado: newStatus, cancelada_por: 'ADMIN' });
      setAppointments((prev) =>
        prev.map((a) => (a.id === aptId ? { ...a, estado: newStatus } : a))
      );
      setAlertNotice(`Cita actualizada exitosamente a estado: ${newStatus}`);
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al actualizar cita:', err);
    }
  };

  // Crear nueva cita
  const handleCreateAppointment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPatientId || !newDoctorId || !newRoomId) return;

    try {
      setIsSubmitting(true);
      const fechaHora = new Date(`${newDate}T${newTime}:00`).toISOString();
      const res = await api.post<Cita>('/citas', {
        paciente_id: newPatientId,
        medico_id: newDoctorId,
        consultorio_id: newRoomId,
        fecha_hora: fechaHora,
        duracion_min: parseInt(newDuration, 10),
        motivo: newMotivo,
      });

      setAppointments((prev) => [res.data, ...prev]);
      setIsNewAptModalOpen(false);
      setNewMotivo('');
      setAlertNotice('¡Nueva cita médica agendada correctamente!');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al crear cita:', err);
    } finally {
      setIsSubmitting(false);
    }
  };

  // Filtrado de citas
  const filteredAppointments = useMemo(() => {
    return appointments.filter((apt) => {
      const matchDoc = selectedDoctorId === 'all' || apt.medico_id === selectedDoctorId;
      const matchStatus = selectedStatus === 'all' || apt.estado === selectedStatus;
      const matchPatient =
        !searchPatient ||
        (apt.paciente?.nombre || '').toLowerCase().includes(searchPatient.toLowerCase());
      return matchDoc && matchStatus && matchPatient;
    });
  }, [appointments, selectedDoctorId, selectedStatus, searchPatient]);

  // Métricas rápidas del día
  const stats = useMemo(() => {
    const total = appointments.length;
    const confirmadas = appointments.filter((a) => a.estado === 'CONFIRMADA').length;
    const sinConfirmar = appointments.filter((a) => a.estado === 'SIN_CONFIRMAR').length;
    const canceladas = appointments.filter((a) => a.estado === 'CANCELADA').length;
    return { total, confirmadas, sinConfirmar, canceladas };
  }, [appointments]);

  return (
    <div className="space-y-6">
      {/* ─────────────────────────────────────────
          CABECERA DE PÁGINA
          ───────────────────────────────────────── */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
            Agenda Médica y Control de Citas
          </h1>
          <p className="text-xs text-[#64748B] mt-1">
            Programación de consultas, estados de recepción y recordatorios WhatsApp en tiempo real.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handleRefresh}
            isLoading={isLoading}
            leftIcon={<RefreshCw className="w-3.5 h-3.5" />}
          >
            Actualizar
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsNewAptModalOpen(true)}
            leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
          >
            Agendar Nueva Cita
          </Button>
        </div>
      </div>

      {alertNotice && (
        <Alert variant="success" title="Notificación">
          {alertNotice}
        </Alert>
      )}

      {/* ─────────────────────────────────────────
          MÉTRICAS DEL DÍA (Clinical Precision Cards)
          ───────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#64748B]">
                Citas Programadas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#0B1C30] mt-1 tnum">
                {stats.total}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center">
              <CalendarIcon className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#047857]">
                Confirmadas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#047857] mt-1 tnum">
                {stats.confirmadas}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#ECFDF5] text-[#059669] flex items-center justify-center">
              <CheckCircle2 className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#B45309]">
                Sin Confirmar
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#B45309] mt-1 tnum">
                {stats.sinConfirmar}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#FFFBEB] text-[#D97706] flex items-center justify-center">
              <Clock className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4 flex items-center justify-between">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[#BE123C]">
                Canceladas
              </p>
              <h3 className="text-2xl font-bold font-heading text-[#BE123C] mt-1 tnum">
                {stats.canceladas}
              </h3>
            </div>
            <div className="w-10 h-10 rounded-xl bg-[#FFF1F2] text-[#E11D48] flex items-center justify-center">
              <XCircle className="w-5 h-5" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ─────────────────────────────────────────
          BARRA DE FILTROS & BÚSQUEDA
          ───────────────────────────────────────── */}
      <Card>
        <CardContent className="p-4">
          <div className="grid grid-cols-1 sm:grid-cols-3 lg:grid-cols-4 gap-3 items-end">
            <div>
              <Input
                label="Buscar Paciente"
                placeholder="Nombre o apellido..."
                value={searchPatient}
                onChange={(e) => setSearchPatient(e.target.value)}
                leftIcon={<User className="w-4 h-4" />}
              />
            </div>

            <div>
              <Select
                label="Médico Responsable"
                value={selectedDoctorId}
                onChange={(e) => setSelectedDoctorId(e.target.value)}
                options={[
                  { value: 'all', label: 'Todos los Médicos' },
                  ...doctors.map((d) => ({
                    value: d.id,
                    label: `${d.nombre} (${d.especialidad || 'Gral.'})`,
                  })),
                ]}
              />
            </div>

            <div>
              <Select
                label="Estado de Cita"
                value={selectedStatus}
                onChange={(e) => setSelectedStatus(e.target.value)}
                options={[
                  { value: 'all', label: 'Todos los Estados' },
                  { value: 'CONFIRMADA', label: 'Confirmadas' },
                  { value: 'SIN_CONFIRMAR', label: 'Sin Confirmar' },
                  { value: 'CANCELADA', label: 'Canceladas' },
                  { value: 'REAGENDADA', label: 'Reagendadas' },
                  { value: 'NO_ASISTIO', label: 'No Asistió' },
                ]}
              />
            </div>

            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                className="w-full"
                onClick={() => {
                  setSelectedDoctorId('all');
                  setSelectedStatus('all');
                  setSearchPatient('');
                }}
                leftIcon={<Filter className="w-3.5 h-3.5" />}
              >
                Limpiar Filtros
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* ─────────────────────────────────────────
          TABLA DE CITAS
          ───────────────────────────────────────── */}
      <div className="space-y-3">
        <div className="flex items-center justify-between text-xs text-[#64748B] px-1">
          <span>
            Mostrando <strong>{filteredAppointments.length}</strong> consultas programadas
          </span>
          <span className="flex items-center gap-1.5 text-[11px]">
            <span className="w-2 h-2 rounded-full bg-[#059669]" />
            Sincronización activa con WhatsApp Job 48h/24h
          </span>
        </div>

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-28">Hora / Duración</TableHead>
              <TableHead>Paciente</TableHead>
              <TableHead>Médico & Espacio</TableHead>
              <TableHead>Motivo de Consulta</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="text-right">Acciones</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredAppointments.length > 0 ? (
              filteredAppointments.map((apt) => {
                const dateObj = new Date(apt.fecha_hora);
                const timeStr = dateObj.toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                });

                return (
                  <TableRow key={apt.id}>
                    {/* Hora y Duración */}
                    <TableCell>
                      <div className="flex items-center gap-1.5 font-bold text-sm text-[#0B1C30] tnum">
                        <Clock className="w-3.5 h-3.5 text-[#0A4D68]" />
                        <span>{timeStr}</span>
                      </div>
                      <span className="text-[11px] text-[#64748B] block mt-0.5 tnum">
                        {apt.duracion_min} minutos
                      </span>
                    </TableCell>

                    {/* Paciente */}
                    <TableCell>
                      <span className="font-semibold text-sm text-[#0B1C30] block">
                        {apt.paciente?.nombre || 'Paciente no registrado'}
                      </span>
                      <span className="text-xs text-[#64748B] flex items-center gap-2 mt-0.5">
                        {apt.paciente?.telefono && <span>Tel. {apt.paciente.telefono}</span>}
                        {apt.paciente?.sexo && (
                          <span className="bg-[#EFF4FF] text-[#0A4D68] px-1.5 py-0.2 rounded text-[10px] font-bold">
                            {apt.paciente.sexo}
                          </span>
                        )}
                      </span>
                    </TableCell>

                    {/* Médico & Consultorio */}
                    <TableCell>
                      <span className="font-medium text-xs text-[#0B1C30] block">
                        {apt.medico?.nombre || 'Médico Asignado'}
                      </span>
                      <span className="text-[11px] text-[#64748B] block mt-0.5">
                        {apt.consultorio?.nombre || 'Consultorio 101'}
                      </span>
                    </TableCell>

                    {/* Motivo */}
                    <TableCell className="max-w-xs">
                      <p className="text-xs text-[#41484D] truncate" title={apt.motivo || ''}>
                        {apt.motivo || 'Consulta de rutina / Revisión general'}
                      </p>
                    </TableCell>

                    {/* Estado */}
                    <TableCell>
                      <Badge variant={apt.estado} />
                    </TableCell>

                    {/* Acciones */}
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        {apt.estado === 'SIN_CONFIRMAR' && (
                          <button
                            type="button"
                            onClick={() => handleUpdateStatus(apt.id, 'CONFIRMADA')}
                            className="p-1.5 rounded-lg border border-[#A7F3D0] bg-[#ECFDF5] text-[#047857] hover:bg-[#d1fae5] transition-colors cursor-pointer"
                            title="Confirmar asistencia"
                          >
                            <CheckCircle2 className="w-4 h-4" />
                          </button>
                        )}

                        {apt.estado !== 'CANCELADA' && (
                          <button
                            type="button"
                            onClick={() => handleUpdateStatus(apt.id, 'CANCELADA')}
                            className="p-1.5 rounded-lg border border-[#FECDD3] bg-[#FFF1F2] text-[#BE123C] hover:bg-[#ffe4e6] transition-colors cursor-pointer"
                            title="Cancelar cita"
                          >
                            <XCircle className="w-4 h-4" />
                          </button>
                        )}

                        <a
                          href={`#expediente-${apt.paciente_id}`}
                          onClick={(e) => {
                            e.preventDefault();
                            alert(
                              `Abriendo Expediente Clínico NOM-004 de: ${
                                apt.paciente?.nombre || 'Paciente'
                              }`
                            );
                          }}
                          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#0A4D68] text-white hover:bg-[#00354a] text-xs font-medium transition-all shadow-2xs"
                        >
                          <FileText className="w-3.5 h-3.5 text-[#88BDDC]" />
                          <span className="hidden sm:inline">Expediente</span>
                        </a>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })
            ) : (
              <TableRow>
                <TableCell colSpan={6} className="text-center py-12 text-[#94A3B8]">
                  No se encontraron citas con los filtros seleccionados.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* ─────────────────────────────────────────
          MODAL DE AGENDAR NUEVA CITA MÉDICA
          ───────────────────────────────────────── */}
      <Modal
        isOpen={isNewAptModalOpen}
        onClose={() => setIsNewAptModalOpen(false)}
        title="Agendar Nueva Consulta Médica"
        description="Selecciona paciente, médico, consultorio y duración según NOM-004."
        maxWidth="lg"
      >
        <form onSubmit={handleCreateAppointment} className="space-y-4">
          <Select
            label="Paciente"
            value={newPatientId}
            onChange={(e) => setNewPatientId(e.target.value)}
            options={patients.map((p) => ({
              value: p.id,
              label: `${p.nombre} (Tel: ${p.telefono || 'Sin tel.'})`,
            }))}
            required
          />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Select
              label="Médico Tratante"
              value={newDoctorId}
              onChange={(e) => {
                setNewDoctorId(e.target.value);
                const doc = doctors.find((d) => d.id === e.target.value);
                if (doc?.duracion_consulta_min) {
                  setNewDuration(doc.duracion_consulta_min.toString());
                }
              }}
              options={doctors.map((d) => ({
                value: d.id,
                label: `${d.nombre} (${d.especialidad || 'Gral.'})`,
              }))}
              required
            />

            <Select
              label="Consultorio Asignado"
              value={newRoomId}
              onChange={(e) => setNewRoomId(e.target.value)}
              options={clinicRooms.map((r) => ({
                value: r.id,
                label: r.nombre,
              }))}
              required
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Input
              label="Fecha de la Cita"
              type="date"
              value={newDate}
              onChange={(e) => setNewDate(e.target.value)}
              required
            />

            <Input
              label="Hora de Inicio"
              type="time"
              value={newTime}
              onChange={(e) => setNewTime(e.target.value)}
              required
            />

            <Select
              label="Duración (Min)"
              value={newDuration}
              onChange={(e) => setNewDuration(e.target.value)}
              options={[
                { value: '20', label: '20 minutos' },
                { value: '30', label: '30 minutos (Estándar)' },
                { value: '45', label: '45 minutos' },
                { value: '60', label: '60 minutos (Extensa)' },
              ]}
              required
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Motivo de Consulta / Síntomas Iniciales
            </label>
            <textarea
              rows={2}
              className="block w-full rounded-lg border border-[#CBD5E1] bg-white p-3 text-sm text-[#0B1C30] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15"
              placeholder="Ej. Cefalea intermitente, revisión de análisis clínicos..."
              value={newMotivo}
              onChange={(e) => setNewMotivo(e.target.value)}
            />
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button
              type="button"
              variant="outline"
              onClick={() => setIsNewAptModalOpen(false)}
            >
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSubmitting}
              leftIcon={<CalendarIcon className="w-4 h-4 text-[#88BDDC]" />}
            >
              Confirmar y Agendar
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
