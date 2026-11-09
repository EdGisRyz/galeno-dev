// Expediente clínico: notas SOAP, diagnósticos y prescripciones.
//
// Sin contrato real: NOM-004/NOM-024 están declaradas fuera de alcance del esquema V1 (ver
// `src/types/futuro.types.ts`), así que esta pantalla solo habla con el mock
// (`src/lib/mock/mockAdapter.ts`, sección "Expediente clínico & SOAP"). El directorio de
// pacientes que alimenta el selector sí es real (`/organizaciones/{id}/pacientes`).

import { useState, useEffect, useMemo } from 'react';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
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
import type { Medico } from '@/features/medicos/medicos.types';
import type { Organizacion } from '@/features/organizaciones/organizaciones.types';
import type { Diagnostico, TipoDiagnostico, EstadoDiagnostico } from '@/features/expediente/expediente.types';
import type { SoapNote, Prescription } from '@/types/futuro.types';
import { useAuthStore } from '@/store/auth.store';
import { descargarRecetaPdf, descargarExpedientePdf } from '@/lib/pdf/expedientePdf';
import {
  FileText,
  ClipboardList,
  Pill,
  Plus,
  Lock,
  MessageSquarePlus,
  Loader2,
  RefreshCw,
  Search,
  Ban,
  Download,
} from 'lucide-react';
import { cn } from '@/lib/utils';

const MENSAJE_ERROR_PACIENTES =
  'No fue posible obtener el directorio de pacientes. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_EXPEDIENTE =
  'No fue posible obtener el expediente de este paciente. Revisa la conexión con el servicio e inténtalo de nuevo.';
const MENSAJE_ERROR_GUARDADO =
  'No fue posible guardar los cambios. Revisa la conexión con el servicio e inténtalo de nuevo.';

const TABS = [
  { key: 'soap', label: 'Notas SOAP', icon: FileText },
  { key: 'diagnosticos', label: 'Diagnósticos', icon: ClipboardList },
  { key: 'prescripciones', label: 'Prescripciones', icon: Pill },
] as const;
type TabKey = (typeof TABS)[number]['key'];

const TEXTAREA_CLASS =
  'block w-full rounded-lg border border-[#CBD5E1] bg-white p-3 text-sm text-[#0B1C30] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15';

const TIPOS_DIAGNOSTICO: TipoDiagnostico[] = ['PRINCIPAL', 'SECUNDARIO', 'PRESUNTIVO'];
const ESTADOS_DIAGNOSTICO: EstadoDiagnostico[] = ['ACTIVO', 'RESUELTO', 'CRONICO'];

const EMPTY_SOAP_FORM = {
  subjetivo: '',
  objetivo: '',
  analisis: '',
  plan: '',
  temperatura: '',
  presion: '',
  fc: '',
  fr: '',
  peso: '',
  talla: '',
};

const EMPTY_DIAGNOSTICO_FORM = {
  codigo_cie10: '',
  descripcion_cie10: '',
  descripcion: '',
  tipo: 'PRINCIPAL' as TipoDiagnostico,
  estado: 'ACTIVO' as EstadoDiagnostico,
  fecha_diagnostico: new Date().toISOString().slice(0, 10),
};

const EMPTY_PRESCRIPCION_FORM = {
  medicamento: '',
  nombre_comercial: '',
  dosis: '',
  via_administracion: '',
  frecuencia: '',
  duracion: '',
  instrucciones: '',
};

export function ExpedientePage() {
  const organizacionId = useAuthStore((state) => state.user?.tenantId ?? '');
  const medicoId = useAuthStore((state) => state.user?.medicoId);
  // Solo un médico con identidad propia puede firmar una nota, un diagnóstico o una receta: un
  // ADMIN sin `medicoId` no tiene autoría clínica que inventar (plan sección 2, guardarraíl 4).
  const puedeRegistrar = useAuthStore((state) => state.user?.role === 'MEDICO') && Boolean(medicoId);

  const rutaPacientes = organizacionId ? `/organizaciones/${organizacionId}/pacientes` : null;
  const rutaMedicos = organizacionId ? `/organizaciones/${organizacionId}/medicos` : null;
  const rutaOrganizacion = organizacionId ? `/organizaciones/${organizacionId}` : null;

  const [pacientes, setPacientes] = useState<Paciente[]>([]);
  const [isLoadingPacientes, setIsLoadingPacientes] = useState(() => rutaPacientes !== null);
  const [pacientesError, setPacientesError] = useState<string | null>(() =>
    rutaPacientes ? null : MENSAJE_ERROR_PACIENTES,
  );
  const [selectedPacienteId, setSelectedPacienteId] = useState('');
  const [activeTab, setActiveTab] = useState<TabKey>('soap');

  // Médico y organización son datos reales: se usan para mostrar quién firma cada nota/receta
  // (NOM-004) y para los datos del establecimiento en el PDF.
  const [medicos, setMedicos] = useState<Medico[]>([]);
  const [organizacion, setOrganizacion] = useState<Organizacion | null>(null);

  const [soapNotes, setSoapNotes] = useState<SoapNote[]>([]);
  const [diagnosticos, setDiagnosticos] = useState<Diagnostico[]>([]);
  const [prescripciones, setPrescripciones] = useState<Prescription[]>([]);
  const [isLoadingExpediente, setIsLoadingExpediente] = useState(false);
  const [expedienteError, setExpedienteError] = useState<string | null>(null);

  const [saveError, setSaveError] = useState<string | null>(null);
  const [alertNotice, setAlertNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!rutaPacientes) return;
    api
      .get<Paciente[]>(rutaPacientes)
      .then((res) => {
        setPacientes(res.data);
        setPacientesError(null);
      })
      .catch((err) => {
        console.error('Error al cargar pacientes:', resumenDeErrorHttp(err));
        setPacientesError(MENSAJE_ERROR_PACIENTES);
      })
      .finally(() => setIsLoadingPacientes(false));
  }, [rutaPacientes]);

  useEffect(() => {
    if (!rutaMedicos) return;
    api
      .get<Medico[]>(rutaMedicos)
      .then((res) => setMedicos(res.data))
      .catch((err) => console.error('Error al cargar médicos:', resumenDeErrorHttp(err)));
  }, [rutaMedicos]);

  useEffect(() => {
    if (!rutaOrganizacion) return;
    api
      .get<Organizacion>(rutaOrganizacion)
      .then((res) => setOrganizacion(res.data))
      .catch((err) => console.error('Error al cargar la organización:', resumenDeErrorHttp(err)));
  }, [rutaOrganizacion]);

  const medicosPorId = useMemo(() => new Map(medicos.map((m) => [m.id, m])), [medicos]);

  const loadExpediente = (pacienteId: string) => {
    return Promise.all([
      api.get<SoapNote[]>(`/expediente/notas?paciente_id=${pacienteId}`),
      api.get<Diagnostico[]>(`/expediente/diagnosticos?paciente_id=${pacienteId}`),
      api.get<Prescription[]>(`/expediente/prescripciones?paciente_id=${pacienteId}`),
    ])
      .then(([notasRes, diagRes, rxRes]) => {
        setSoapNotes(notasRes.data);
        setDiagnosticos(diagRes.data);
        setPrescripciones(rxRes.data);
      })
      .catch((err) => {
        console.error('Error al cargar el expediente:', resumenDeErrorHttp(err));
        setSoapNotes([]);
        setDiagnosticos([]);
        setPrescripciones([]);
        setExpedienteError(MENSAJE_ERROR_EXPEDIENTE);
      })
      .finally(() => setIsLoadingExpediente(false));
  };

  // El directorio es real y ya está cargado: elegir un paciente es un evento del usuario, no un
  // efecto derivado de otro estado, así que la carga arranca aquí y no en un useEffect.
  const iniciarCargaExpediente = (pacienteId: string) => {
    setIsLoadingExpediente(true);
    setExpedienteError(null);
    loadExpediente(pacienteId);
  };

  const handleSelectPaciente = (id: string) => {
    setSelectedPacienteId(id);
    setSoapNotes([]);
    setDiagnosticos([]);
    setPrescripciones([]);
    if (id) iniciarCargaExpediente(id);
  };

  const pacienteSeleccionado = useMemo(
    () => pacientes.find((p) => p.id === selectedPacienteId),
    [pacientes, selectedPacienteId],
  );

  // ───────────────────────────────────────── Notas SOAP
  const [isSoapModalOpen, setIsSoapModalOpen] = useState(false);
  const [soapForm, setSoapForm] = useState(EMPTY_SOAP_FORM);
  const [isSavingSoap, setIsSavingSoap] = useState(false);
  const [correccionDeId, setCorreccionDeId] = useState<string | null>(null);
  const [correccionTexto, setCorreccionTexto] = useState('');
  const [isSavingCorreccion, setIsSavingCorreccion] = useState(false);
  const [cerrandoId, setCerrandoId] = useState<string | null>(null);

  const handleSoapSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPacienteId || !soapForm.subjetivo.trim() || !soapForm.analisis.trim()) return;

    const peso = soapForm.peso ? Number(soapForm.peso) : undefined;
    const talla = soapForm.talla ? Number(soapForm.talla) : undefined;
    const payload = {
      paciente_id: selectedPacienteId,
      medico_id: medicoId,
      subjetivo: soapForm.subjetivo,
      objetivo: soapForm.objetivo,
      analisis: soapForm.analisis,
      plan: soapForm.plan,
      signos_vitales: {
        temperatura: soapForm.temperatura ? Number(soapForm.temperatura) : undefined,
        presion: soapForm.presion || undefined,
        fc: soapForm.fc ? Number(soapForm.fc) : undefined,
        fr: soapForm.fr ? Number(soapForm.fr) : undefined,
        peso,
        talla,
        imc: peso && talla ? Number((peso / (talla * talla)).toFixed(2)) : undefined,
      },
    };

    try {
      setIsSavingSoap(true);
      setSaveError(null);
      const res = await api.post<SoapNote>('/expediente/notas', payload);
      setSoapNotes((prev) => [res.data, ...prev]);
      setIsSoapModalOpen(false);
      setSoapForm(EMPTY_SOAP_FORM);
      setAlertNotice('Nota SOAP registrada correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar la nota SOAP:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSavingSoap(false);
    }
  };

  const handleCerrarNota = async (id: string) => {
    try {
      setCerrandoId(id);
      const res = await api.post<SoapNote>(`/expediente/notas/${id}/cerrar`);
      setSoapNotes((prev) => prev.map((n) => (n.id === id ? res.data : n)));
    } catch (err) {
      console.error('Error al cerrar la nota:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setCerrandoId(null);
    }
  };

  const handleAgregarCorreccion = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!correccionDeId || !correccionTexto.trim()) return;
    try {
      setIsSavingCorreccion(true);
      const res = await api.post<SoapNote>(`/expediente/notas/${correccionDeId}/correccion`, {
        medico_id: medicoId,
        texto: correccionTexto,
      });
      setSoapNotes((prev) => prev.map((n) => (n.id === correccionDeId ? res.data : n)));
      setCorreccionDeId(null);
      setCorreccionTexto('');
    } catch (err) {
      console.error('Error al agregar la corrección:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSavingCorreccion(false);
    }
  };

  // ───────────────────────────────────────── Diagnósticos
  const [isDiagModalOpen, setIsDiagModalOpen] = useState(false);
  const [diagForm, setDiagForm] = useState(EMPTY_DIAGNOSTICO_FORM);
  const [isSavingDiag, setIsSavingDiag] = useState(false);
  const [cie10Query, setCie10Query] = useState('');
  const [cie10Results, setCie10Results] = useState<{ codigo: string; descripcion: string }[]>([]);

  const handleCie10QueryChange = (value: string) => {
    setCie10Query(value);
    // Limpiar resultados al vaciar el campo es una reacción directa a este evento, no algo que
    // deba esperar al debounce del efecto.
    if (!value.trim()) setCie10Results([]);
  };

  useEffect(() => {
    if (!cie10Query.trim()) return;
    const timeout = setTimeout(() => {
      api
        .get<{ codigo: string; descripcion: string }[]>(
          `/expediente/cie10/buscar?q=${encodeURIComponent(cie10Query)}`,
        )
        .then((res) => setCie10Results(res.data))
        .catch(() => setCie10Results([]));
    }, 250);
    return () => clearTimeout(timeout);
  }, [cie10Query]);

  const handleDiagSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPacienteId || !diagForm.codigo_cie10 || !diagForm.descripcion.trim()) return;

    const payload = {
      paciente_id: selectedPacienteId,
      codigo_cie10: diagForm.codigo_cie10,
      descripcion_cie10: diagForm.descripcion_cie10 || null,
      descripcion: diagForm.descripcion,
      tipo: diagForm.tipo,
      estado: diagForm.estado,
      fecha_diagnostico: diagForm.fecha_diagnostico || null,
    };

    try {
      setIsSavingDiag(true);
      setSaveError(null);
      const res = await api.post<Diagnostico>('/expediente/diagnosticos', payload);
      setDiagnosticos((prev) => [res.data, ...prev]);
      setIsDiagModalOpen(false);
      setDiagForm(EMPTY_DIAGNOSTICO_FORM);
      setCie10Query('');
      setAlertNotice('Diagnóstico registrado correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar el diagnóstico:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSavingDiag(false);
    }
  };

  // ───────────────────────────────────────── Prescripciones
  const [isRxModalOpen, setIsRxModalOpen] = useState(false);
  const [rxForm, setRxForm] = useState(EMPTY_PRESCRIPCION_FORM);
  const [isSavingRx, setIsSavingRx] = useState(false);
  const [desactivandoRxId, setDesactivandoRxId] = useState<string | null>(null);

  const handleRxSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPacienteId || !rxForm.medicamento.trim() || !rxForm.dosis.trim()) return;

    const payload = {
      paciente_id: selectedPacienteId,
      medico_id: medicoId,
      medicamento: rxForm.medicamento,
      nombre_comercial: rxForm.nombre_comercial || null,
      dosis: rxForm.dosis,
      via_administracion: rxForm.via_administracion || null,
      frecuencia: rxForm.frecuencia,
      duracion: rxForm.duracion,
      instrucciones: rxForm.instrucciones || null,
    };

    try {
      setIsSavingRx(true);
      setSaveError(null);
      const res = await api.post<Prescription>('/expediente/prescripciones', payload);
      setPrescripciones((prev) => [res.data, ...prev]);
      setIsRxModalOpen(false);
      setRxForm(EMPTY_PRESCRIPCION_FORM);
      setAlertNotice('Prescripción registrada correctamente.');
      setTimeout(() => setAlertNotice(null), 3500);
    } catch (err) {
      console.error('Error al guardar la prescripción:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setIsSavingRx(false);
    }
  };

  const handleDesactivarRx = async (id: string) => {
    try {
      setDesactivandoRxId(id);
      const res = await api.post<Prescription>(`/expediente/prescripciones/${id}/desactivar`);
      setPrescripciones((prev) => prev.map((p) => (p.id === id ? res.data : p)));
    } catch (err) {
      console.error('Error al desactivar la prescripción:', resumenDeErrorHttp(err));
      setSaveError(MENSAJE_ERROR_GUARDADO);
    } finally {
      setDesactivandoRxId(null);
    }
  };

  const handleDescargarReceta = (prescripcion: Prescription) => {
    if (!organizacion || !pacienteSeleccionado) return;
    descargarRecetaPdf({
      organizacion,
      medico: medicosPorId.get(prescripcion.medico_id),
      paciente: pacienteSeleccionado,
      prescripcion,
    });
  };

  const handleDescargarExpediente = () => {
    if (!organizacion || !pacienteSeleccionado) return;
    descargarExpedientePdf({
      organizacion,
      paciente: pacienteSeleccionado,
      medicosPorId,
      soapNotes,
      diagnosticos,
      prescripciones,
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
          Expediente Clínico
        </h1>
        <p className="text-xs text-[#64748B] mt-1">
          Notas SOAP, diagnósticos y prescripciones por paciente.
        </p>
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

      <Card>
        <CardContent className="p-4">
          {pacientesError ? (
            <Alert variant="error" title="No se pudo cargar el directorio de pacientes">
              {pacientesError}
            </Alert>
          ) : (
            <Select
              label="Paciente"
              value={selectedPacienteId}
              onChange={(e) => handleSelectPaciente(e.target.value)}
              options={pacientes.map((p) => ({ value: p.id, label: p.nombre }))}
              placeholder={isLoadingPacientes ? 'Cargando pacientes...' : 'Selecciona un paciente...'}
              disabled={isLoadingPacientes}
            />
          )}
        </CardContent>
      </Card>

      {!selectedPacienteId ? (
        <p className="text-sm text-[#94A3B8] text-center py-12">
          Selecciona un paciente para ver su expediente clínico.
        </p>
      ) : (
        <>
          {/* ─────────────────────────────────────────
              PESTAÑAS
              ───────────────────────────────────────── */}
          <div className="flex items-center justify-between border-b border-[#E2E8F0]">
            <div className="flex items-center gap-1">
              {TABS.map((tab) => {
                const Icon = tab.icon;
                const isActive = activeTab === tab.key;
                return (
                  <button
                    key={tab.key}
                    type="button"
                    onClick={() => setActiveTab(tab.key)}
                    className={cn(
                      'inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-semibold border-b-2 -mb-px transition-colors',
                      isActive
                        ? 'border-[#0A4D68] text-[#0A4D68]'
                        : 'border-transparent text-[#64748B] hover:text-[#0B1C30]',
                    )}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    {tab.label}
                  </button>
                );
              })}
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={handleDescargarExpediente}
              disabled={!organizacion}
              leftIcon={<Download className="w-3.5 h-3.5" />}
            >
              Descargar expediente (PDF)
            </Button>
          </div>

          {expedienteError ? (
            <Alert variant="error" title="No se pudo cargar el expediente">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <span>{expedienteError}</span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => iniciarCargaExpediente(selectedPacienteId)}
                  leftIcon={<RefreshCw className="w-3.5 h-3.5" />}
                >
                  Reintentar
                </Button>
              </div>
            </Alert>
          ) : isLoadingExpediente ? (
            <div className="flex items-center justify-center py-12">
              <span
                role="status"
                aria-live="polite"
                className="inline-flex items-center gap-2 text-[#64748B] text-sm"
              >
                <Loader2 className="w-4 h-4 animate-spin" />
                Cargando expediente de {pacienteSeleccionado?.nombre}...
              </span>
            </div>
          ) : (
            <>
              {/* ───────────────────────────────── NOTAS SOAP */}
              {activeTab === 'soap' && (
                <div className="space-y-3">
                  {puedeRegistrar && (
                    <div className="flex justify-end">
                      <Button
                        variant="primary"
                        size="sm"
                        onClick={() => setIsSoapModalOpen(true)}
                        leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
                      >
                        Nueva nota SOAP
                      </Button>
                    </div>
                  )}

                  {soapNotes.length === 0 ? (
                    <p className="text-sm text-[#94A3B8] text-center py-8">
                      Este paciente todavía no tiene notas SOAP registradas.
                    </p>
                  ) : (
                    soapNotes.map((n) => (
                      <Card key={n.id}>
                        <CardContent className="p-5 space-y-3">
                          <div className="flex items-center justify-between">
                            <div>
                              <span className="text-xs text-[#64748B] block">
                                {new Date(n.created_at).toLocaleString('es-MX')}
                              </span>
                              {/* NOM-004: toda nota identifica al médico que la generó. */}
                              <span className="text-[11px] text-[#0A4D68] font-semibold">
                                {medicosPorId.get(n.medico_id)?.nombre || 'Médico no identificado'}
                                {medicosPorId.get(n.medico_id)?.cedula &&
                                  ` · Céd. ${medicosPorId.get(n.medico_id)?.cedula}`}
                              </span>
                            </div>
                            <div className="flex items-center gap-2">
                              <Badge variant={n.inmutable ? 'default' : 'success'} showDot>
                                {n.inmutable ? 'Cerrada' : 'Abierta'}
                              </Badge>
                              {!n.inmutable && puedeRegistrar && (
                                <Button
                                  variant="outline"
                                  size="sm"
                                  isLoading={cerrandoId === n.id}
                                  onClick={() => handleCerrarNota(n.id)}
                                  leftIcon={<Lock className="w-3.5 h-3.5" />}
                                >
                                  Cerrar nota
                                </Button>
                              )}
                              {n.inmutable && puedeRegistrar && (
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => setCorreccionDeId(n.id)}
                                  leftIcon={<MessageSquarePlus className="w-3.5 h-3.5" />}
                                >
                                  Corregir
                                </Button>
                              )}
                            </div>
                          </div>

                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
                            <div>
                              <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B]">
                                S · Subjetivo
                              </p>
                              <p className="text-[#41484D] whitespace-pre-wrap">{n.subjetivo}</p>
                            </div>
                            <div>
                              <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B]">
                                O · Objetivo
                              </p>
                              <p className="text-[#41484D] whitespace-pre-wrap">{n.objetivo}</p>
                            </div>
                            <div>
                              <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B]">
                                A · Análisis
                              </p>
                              <p className="text-[#41484D] whitespace-pre-wrap">{n.analisis}</p>
                            </div>
                            <div>
                              <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B]">
                                P · Plan
                              </p>
                              <p className="text-[#41484D] whitespace-pre-wrap">{n.plan}</p>
                            </div>
                          </div>

                          {n.signos_vitales && (
                            <div className="flex flex-wrap gap-2 pt-2 border-t border-[#F1F5F9]">
                              {n.signos_vitales.temperatura != null && (
                                <Badge variant="default">T° {n.signos_vitales.temperatura}</Badge>
                              )}
                              {n.signos_vitales.presion && (
                                <Badge variant="default">TA {n.signos_vitales.presion}</Badge>
                              )}
                              {n.signos_vitales.fc != null && (
                                <Badge variant="default">FC {n.signos_vitales.fc}</Badge>
                              )}
                              {n.signos_vitales.fr != null && (
                                <Badge variant="default">FR {n.signos_vitales.fr}</Badge>
                              )}
                              {n.signos_vitales.peso != null && (
                                <Badge variant="default">Peso {n.signos_vitales.peso}kg</Badge>
                              )}
                              {n.signos_vitales.talla != null && (
                                <Badge variant="default">Talla {n.signos_vitales.talla}m</Badge>
                              )}
                              {n.signos_vitales.imc != null && (
                                <Badge variant="default">IMC {n.signos_vitales.imc}</Badge>
                              )}
                            </div>
                          )}

                          {n.correcciones && n.correcciones.length > 0 && (
                            <div className="pt-2 border-t border-[#F1F5F9] space-y-1.5">
                              <p className="text-[11px] font-bold uppercase tracking-wider text-[#B45309]">
                                Correcciones
                              </p>
                              {n.correcciones.map((c) => (
                                <p key={c.id} className="text-xs text-[#41484D]">
                                  <span className="text-[#94A3B8]">
                                    {new Date(c.created_at).toLocaleString('es-MX')} ·{' '}
                                    {medicosPorId.get(c.medico_id)?.nombre || 'Médico no identificado'}:
                                  </span>{' '}
                                  {c.texto}
                                </p>
                              ))}
                            </div>
                          )}
                        </CardContent>
                      </Card>
                    ))
                  )}
                </div>
              )}

              {/* ───────────────────────────────── DIAGNÓSTICOS */}
              {activeTab === 'diagnosticos' && (
                <div className="space-y-3">
                  {puedeRegistrar && (
                    <div className="flex justify-end">
                      <Button
                        variant="primary"
                        size="sm"
                        onClick={() => setIsDiagModalOpen(true)}
                        leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
                      >
                        Nuevo diagnóstico
                      </Button>
                    </div>
                  )}

                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>CIE-10</TableHead>
                        <TableHead>Descripción</TableHead>
                        <TableHead>Tipo</TableHead>
                        <TableHead>Estado</TableHead>
                        <TableHead>Fecha</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {diagnosticos.length > 0 ? (
                        diagnosticos.map((d) => (
                          <TableRow key={d.id}>
                            <TableCell>
                              <span className="text-xs font-mono text-[#0A4D68]">
                                {d.codigo_cie10}
                              </span>
                            </TableCell>
                            <TableCell>
                              <span className="text-xs text-[#41484D]">{d.descripcion}</span>
                            </TableCell>
                            <TableCell>
                              <Badge variant="default">{d.tipo}</Badge>
                            </TableCell>
                            <TableCell>
                              <Badge variant={d.estado === 'ACTIVO' ? 'success' : 'default'} showDot>
                                {d.estado}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              <span className="text-xs text-[#64748B]">
                                {d.fecha_diagnostico || '—'}
                              </span>
                            </TableCell>
                          </TableRow>
                        ))
                      ) : (
                        <TableRow>
                          <TableCell colSpan={5} className="text-center py-8 text-[#94A3B8]">
                            Este paciente todavía no tiene diagnósticos registrados.
                          </TableCell>
                        </TableRow>
                      )}
                    </TableBody>
                  </Table>
                </div>
              )}

              {/* ───────────────────────────────── PRESCRIPCIONES */}
              {activeTab === 'prescripciones' && (
                <div className="space-y-3">
                  {puedeRegistrar && (
                    <div className="flex justify-end">
                      <Button
                        variant="primary"
                        size="sm"
                        onClick={() => setIsRxModalOpen(true)}
                        leftIcon={<Plus className="w-4 h-4 text-[#88BDDC]" />}
                      >
                        Nueva prescripción
                      </Button>
                    </div>
                  )}

                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Medicamento</TableHead>
                        <TableHead>Dosis</TableHead>
                        <TableHead>Frecuencia / Duración</TableHead>
                        <TableHead>Médico</TableHead>
                        <TableHead>Estado</TableHead>
                        <TableHead className="text-right">Acciones</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {prescripciones.length > 0 ? (
                        prescripciones.map((p) => (
                          <TableRow key={p.id}>
                            <TableCell>
                              <span className="text-xs font-semibold text-[#0B1C30] block">
                                {p.medicamento}
                              </span>
                              {p.nombre_comercial && (
                                <span className="text-[11px] text-[#64748B]">
                                  {p.nombre_comercial}
                                </span>
                              )}
                            </TableCell>
                            <TableCell>
                              <span className="text-xs text-[#41484D]">
                                {p.dosis}
                                {p.via_administracion ? ` · ${p.via_administracion}` : ''}
                              </span>
                            </TableCell>
                            <TableCell>
                              <span className="text-xs text-[#41484D]">
                                {p.frecuencia} · {p.duracion}
                              </span>
                            </TableCell>
                            <TableCell>
                              <span className="text-xs text-[#41484D]">
                                {medicosPorId.get(p.medico_id)?.nombre || 'No identificado'}
                              </span>
                            </TableCell>
                            <TableCell>
                              <Badge variant={p.activo ? 'success' : 'default'} showDot>
                                {p.activo ? 'Activa' : 'Suspendida'}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right">
                              <div className="inline-flex items-center gap-1.5">
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => handleDescargarReceta(p)}
                                  disabled={!organizacion}
                                  leftIcon={<Download className="w-3.5 h-3.5" />}
                                >
                                  Receta
                                </Button>
                                {puedeRegistrar && p.activo && (
                                  <Button
                                    variant="outline"
                                    size="sm"
                                    isLoading={desactivandoRxId === p.id}
                                    onClick={() => handleDesactivarRx(p.id)}
                                    leftIcon={<Ban className="w-3.5 h-3.5" />}
                                  >
                                    Suspender
                                  </Button>
                                )}
                              </div>
                            </TableCell>
                          </TableRow>
                        ))
                      ) : (
                        <TableRow>
                          <TableCell colSpan={6} className="text-center py-8 text-[#94A3B8]">
                            Este paciente todavía no tiene prescripciones registradas.
                          </TableCell>
                        </TableRow>
                      )}
                    </TableBody>
                  </Table>
                </div>
              )}
            </>
          )}
        </>
      )}

      {/* ───────────────────────────────── MODAL NOTA SOAP */}
      <Modal
        isOpen={isSoapModalOpen}
        onClose={() => setIsSoapModalOpen(false)}
        title="Nueva nota SOAP"
        description={`Paciente: ${pacienteSeleccionado?.nombre || ''}`}
        maxWidth="lg"
      >
        <form onSubmit={handleSoapSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Subjetivo <span className="text-[#E11D48]">*</span>
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={soapForm.subjetivo}
              onChange={(e) => setSoapForm((f) => ({ ...f, subjetivo: e.target.value }))}
              required
            />
          </div>
          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Objetivo
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={soapForm.objetivo}
              onChange={(e) => setSoapForm((f) => ({ ...f, objetivo: e.target.value }))}
            />
          </div>

          <div className="grid grid-cols-3 sm:grid-cols-6 gap-3">
            <Input
              label="Temp. (°C)"
              type="number"
              step="0.1"
              value={soapForm.temperatura}
              onChange={(e) => setSoapForm((f) => ({ ...f, temperatura: e.target.value }))}
            />
            <Input
              label="TA"
              placeholder="120/80"
              value={soapForm.presion}
              onChange={(e) => setSoapForm((f) => ({ ...f, presion: e.target.value }))}
            />
            <Input
              label="FC"
              type="number"
              value={soapForm.fc}
              onChange={(e) => setSoapForm((f) => ({ ...f, fc: e.target.value }))}
            />
            <Input
              label="FR"
              type="number"
              value={soapForm.fr}
              onChange={(e) => setSoapForm((f) => ({ ...f, fr: e.target.value }))}
            />
            <Input
              label="Peso (kg)"
              type="number"
              step="0.1"
              value={soapForm.peso}
              onChange={(e) => setSoapForm((f) => ({ ...f, peso: e.target.value }))}
            />
            <Input
              label="Talla (m)"
              type="number"
              step="0.01"
              value={soapForm.talla}
              onChange={(e) => setSoapForm((f) => ({ ...f, talla: e.target.value }))}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Análisis <span className="text-[#E11D48]">*</span>
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={soapForm.analisis}
              onChange={(e) => setSoapForm((f) => ({ ...f, analisis: e.target.value }))}
              required
            />
          </div>
          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Plan
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={soapForm.plan}
              onChange={(e) => setSoapForm((f) => ({ ...f, plan: e.target.value }))}
            />
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsSoapModalOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSavingSoap}>
              Registrar nota
            </Button>
          </div>
        </form>
      </Modal>

      {/* ───────────────────────────────── MODAL CORRECCIÓN */}
      <Modal
        isOpen={correccionDeId !== null}
        onClose={() => setCorreccionDeId(null)}
        title="Agregar corrección"
        description="La nota original ya es inmutable (NOM-004): la corrección se agrega como anexo, nunca reemplaza el texto cerrado."
        maxWidth="sm"
      >
        <form onSubmit={handleAgregarCorreccion} className="space-y-4">
          <textarea
            rows={3}
            className={TEXTAREA_CLASS}
            placeholder="Texto de la corrección..."
            value={correccionTexto}
            onChange={(e) => setCorreccionTexto(e.target.value)}
            required
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setCorreccionDeId(null)}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSavingCorreccion}>
              Agregar
            </Button>
          </div>
        </form>
      </Modal>

      {/* ───────────────────────────────── MODAL DIAGNÓSTICO */}
      <Modal
        isOpen={isDiagModalOpen}
        onClose={() => setIsDiagModalOpen(false)}
        title="Nuevo diagnóstico"
        description={`Paciente: ${pacienteSeleccionado?.nombre || ''}`}
        maxWidth="md"
      >
        <form onSubmit={handleDiagSubmit} className="space-y-4">
          <div className="relative">
            <Input
              label="Buscar en catálogo CIE-10"
              placeholder="Código o descripción..."
              value={cie10Query}
              onChange={(e) => handleCie10QueryChange(e.target.value)}
              leftIcon={<Search className="w-4 h-4" />}
            />
            {cie10Results.length > 0 && (
              <ul className="absolute z-10 mt-1 w-full max-h-48 overflow-y-auto rounded-lg border border-[#E2E8F0] bg-white shadow-lg">
                {cie10Results.map((r) => (
                  <li key={r.codigo}>
                    <button
                      type="button"
                      className="w-full text-left px-3 py-2 text-xs hover:bg-[#F1F5F9]"
                      onClick={() => {
                        setDiagForm((f) => ({
                          ...f,
                          codigo_cie10: r.codigo,
                          descripcion_cie10: r.descripcion,
                        }));
                        setCie10Query('');
                        setCie10Results([]);
                      }}
                    >
                      <span className="font-mono font-semibold text-[#0A4D68]">{r.codigo}</span>{' '}
                      {r.descripcion}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {diagForm.codigo_cie10 && (
            <p className="text-xs text-[#64748B]">
              Seleccionado: <span className="font-mono text-[#0A4D68]">{diagForm.codigo_cie10}</span>{' '}
              — {diagForm.descripcion_cie10}
            </p>
          )}

          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Descripción clínica <span className="text-[#E11D48]">*</span>
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={diagForm.descripcion}
              onChange={(e) => setDiagForm((f) => ({ ...f, descripcion: e.target.value }))}
              required
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Select
              label="Tipo"
              value={diagForm.tipo}
              onChange={(e) =>
                setDiagForm((f) => ({ ...f, tipo: e.target.value as TipoDiagnostico }))
              }
              options={TIPOS_DIAGNOSTICO.map((t) => ({ value: t, label: t }))}
            />
            <Select
              label="Estado"
              value={diagForm.estado}
              onChange={(e) =>
                setDiagForm((f) => ({ ...f, estado: e.target.value as EstadoDiagnostico }))
              }
              options={ESTADOS_DIAGNOSTICO.map((e) => ({ value: e, label: e }))}
            />
            <Input
              label="Fecha"
              type="date"
              value={diagForm.fecha_diagnostico}
              onChange={(e) => setDiagForm((f) => ({ ...f, fecha_diagnostico: e.target.value }))}
            />
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsDiagModalOpen(false)}>
              Cancelar
            </Button>
            <Button
              type="submit"
              variant="primary"
              isLoading={isSavingDiag}
              disabled={!diagForm.codigo_cie10}
            >
              Registrar diagnóstico
            </Button>
          </div>
        </form>
      </Modal>

      {/* ───────────────────────────────── MODAL PRESCRIPCIÓN */}
      <Modal
        isOpen={isRxModalOpen}
        onClose={() => setIsRxModalOpen(false)}
        title="Nueva prescripción"
        description={`Paciente: ${pacienteSeleccionado?.nombre || ''}`}
        maxWidth="md"
      >
        <form onSubmit={handleRxSubmit} className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="Medicamento"
              value={rxForm.medicamento}
              onChange={(e) => setRxForm((f) => ({ ...f, medicamento: e.target.value }))}
              required
            />
            <Input
              label="Nombre comercial"
              value={rxForm.nombre_comercial}
              onChange={(e) => setRxForm((f) => ({ ...f, nombre_comercial: e.target.value }))}
            />
            <Input
              label="Dosis"
              placeholder="500 mg"
              value={rxForm.dosis}
              onChange={(e) => setRxForm((f) => ({ ...f, dosis: e.target.value }))}
              required
            />
            <Input
              label="Vía de administración"
              placeholder="Oral"
              value={rxForm.via_administracion}
              onChange={(e) => setRxForm((f) => ({ ...f, via_administracion: e.target.value }))}
            />
            <Input
              label="Frecuencia"
              placeholder="Cada 8 horas"
              value={rxForm.frecuencia}
              onChange={(e) => setRxForm((f) => ({ ...f, frecuencia: e.target.value }))}
              required
            />
            <Input
              label="Duración"
              placeholder="3 días"
              value={rxForm.duracion}
              onChange={(e) => setRxForm((f) => ({ ...f, duracion: e.target.value }))}
              required
            />
          </div>
          <div>
            <label className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5">
              Instrucciones
            </label>
            <textarea
              rows={2}
              className={TEXTAREA_CLASS}
              value={rxForm.instrucciones}
              onChange={(e) => setRxForm((f) => ({ ...f, instrucciones: e.target.value }))}
            />
          </div>

          <div className="flex justify-end gap-2 pt-4 border-t border-[#F1F5F9]">
            <Button type="button" variant="outline" onClick={() => setIsRxModalOpen(false)}>
              Cancelar
            </Button>
            <Button type="submit" variant="primary" isLoading={isSavingRx}>
              Registrar prescripción
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
