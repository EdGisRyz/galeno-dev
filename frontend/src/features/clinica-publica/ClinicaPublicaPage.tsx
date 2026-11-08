// Página pública de clínica (directorio médico), sin login.
//
// Sin contrato real todavía: ningún GET de organizaciones o médicos es público en el backend
// (ver `mockAdapter.ts`, sección "Directorio público de clínica"). Esta pantalla solo habla con
// el mock mientras ese contrato no exista. La URL es por ruta (`/clinica/:organizacionId`) y no
// por subdominio real: no hay infraestructura de subdominios en este entorno de desarrollo.

import { useState, useEffect, useMemo } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api } from '@/lib/axios';
import { resumenDeErrorHttp } from '@/lib/apiError';
import { Button, Input, Badge, Modal, Card, CardContent } from '@/components/ui';
import type { Medico, HorarioMedico } from '@/features/medicos/medicos.types';
import type { Organizacion } from '@/features/organizaciones/organizaciones.types';
import { Stethoscope, Search, Clock, IdCard, MapPin } from 'lucide-react';

type MedicoPublico = Medico & { horarios: HorarioMedico[]; consultorio?: string };

const DIAS = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];

/** Colores de avatar por iniciales, en el mismo espíritu que el ejemplo de referencia (CMCE). */
const COLORES_AVATAR = ['#BE123C', '#15803D', '#0A4D68', '#7C3AED', '#B45309', '#0369A1'];

function colorDeAvatar(nombre: string): string {
  const indice = nombre.charCodeAt(0) % COLORES_AVATAR.length;
  return COLORES_AVATAR[indice];
}

function iniciales(nombre: string): string {
  const partes = nombre.replace(/^(Dra?\.|Lic\.)\s*/i, '').split(' ').filter(Boolean);
  return ((partes[0]?.[0] || '') + (partes[1]?.[0] || '')).toUpperCase();
}

/** Agrupa horarios consecutivos con el mismo rango de hora en un solo renglón ("Lunes a Viernes"). */
function agruparHorarios(horarios: HorarioMedico[]): string[] {
  const ordenados = [...horarios].sort((a, b) => a.dia_semana - b.dia_semana);
  const grupos: { dias: number[]; inicio: string; fin: string }[] = [];

  for (const h of ordenados) {
    const ultimo = grupos[grupos.length - 1];
    const esConsecutivo =
      ultimo &&
      ultimo.inicio === h.hora_inicio &&
      ultimo.fin === h.hora_fin &&
      h.dia_semana === ultimo.dias[ultimo.dias.length - 1] + 1;

    if (esConsecutivo) {
      ultimo.dias.push(h.dia_semana);
    } else {
      grupos.push({ dias: [h.dia_semana], inicio: h.hora_inicio, fin: h.hora_fin });
    }
  }

  return grupos.map((g) => {
    const etiquetaDias =
      g.dias.length > 1 ? `${DIAS[g.dias[0]]} a ${DIAS[g.dias[g.dias.length - 1]]}` : DIAS[g.dias[0]];
    return `${etiquetaDias}: ${g.inicio.slice(0, 5)} - ${g.fin.slice(0, 5)}`;
  });
}

const MENSAJE_ERROR = 'No fue posible cargar el directorio de esta clínica. Inténtalo de nuevo.';

export function ClinicaPublicaPage() {
  const { organizacionId } = useParams<{ organizacionId: string }>();

  const [organizacion, setOrganizacion] = useState<Organizacion | null>(null);
  const [medicos, setMedicos] = useState<MedicoPublico[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [medicoSeleccionado, setMedicoSeleccionado] = useState<MedicoPublico | null>(null);

  useEffect(() => {
    if (!organizacionId) return;

    Promise.all([
      api.get<Organizacion>(`/publico/clinicas/${organizacionId}`, { validateStatus: () => true }),
      api.get<MedicoPublico[]>(`/publico/clinicas/${organizacionId}/medicos`),
    ])
      .then(([orgRes, medicosRes]) => {
        // El adaptador de mock resuelve siempre (nunca rechaza), status incluido: un 404 de
        // clínica inexistente llega aquí como respuesta normal, no como excepción. `validateStatus`
        // evita que axios lo trate como error contra el backend real, donde sí rechazaría.
        if (orgRes.status === 404) {
          setNotFound(true);
          return;
        }
        setOrganizacion(orgRes.data);
        setMedicos(medicosRes.data);
      })
      .catch((err) => {
        console.error('Error al cargar directorio público:', resumenDeErrorHttp(err));
        setLoadError(MENSAJE_ERROR);
      })
      .finally(() => setIsLoading(false));
  }, [organizacionId]);

  const medicosFiltrados = useMemo(() => {
    const term = search.toLowerCase();
    return medicos.filter(
      (m) =>
        !term ||
        m.nombre.toLowerCase().includes(term) ||
        (m.especialidad || '').toLowerCase().includes(term),
    );
  }, [medicos, search]);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-[#F8FAFC] flex items-center justify-center text-sm text-[#64748B]">
        Cargando directorio...
      </div>
    );
  }

  if (notFound) {
    return (
      <div className="min-h-screen bg-[#F8FAFC] flex flex-col items-center justify-center text-center px-4">
        <h1 className="font-heading font-bold text-xl text-[#0B1C30]">Clínica no encontrada</h1>
        <p className="mt-2 text-sm text-[#64748B]">
          El enlace que seguiste no corresponde a ninguna clínica registrada en Vitalix.
        </p>
        <Link to="/" className="mt-4 text-sm font-medium text-[#0A4D68] hover:text-[#00354a]">
          Ir a la página de Vitalix
        </Link>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#F8FAFC]">
      {/* ─────────────────────────────────────────
          ENCABEZADO DE LA CLÍNICA
          ───────────────────────────────────────── */}
      <header className="bg-[#0A4D68] text-white">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-10 text-center">
          <div className="w-12 h-12 rounded-2xl bg-white/10 text-[#88BDDC] flex items-center justify-center mx-auto mb-4">
            <Stethoscope className="w-6 h-6" />
          </div>
          <h1 className="font-heading font-bold text-2xl sm:text-3xl">
            {organizacion?.nombre}
          </h1>
          <p className="mt-2 text-sm text-[#BBD8F5]">
            Conoce a nuestro equipo médico y sus horarios de atención.
          </p>
        </div>
      </header>

      <div className="max-w-5xl mx-auto px-4 sm:px-6 py-10">
        {loadError && (
          <Card className="mb-6">
            <CardContent className="p-4 text-sm text-[#BE123C]">{loadError}</CardContent>
          </Card>
        )}

        <Input
          placeholder="Buscar por nombre de doctor o especialidad..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          leftIcon={<Search className="w-4 h-4" />}
          className="mb-6"
        />

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
          {medicosFiltrados.map((medico) => (
            <Card key={medico.id}>
              <div
                className="h-28 flex items-center justify-center text-white font-heading font-bold text-3xl rounded-t-2xl"
                style={{ backgroundColor: colorDeAvatar(medico.nombre) }}
              >
                {iniciales(medico.nombre)}
              </div>
              <CardContent className="p-4">
                <h3 className="font-heading font-semibold text-sm text-[#0B1C30]">
                  {medico.nombre}
                </h3>
                {medico.especialidad && (
                  <Badge variant="primary" showDot={false} className="mt-1.5">
                    {medico.especialidad}
                  </Badge>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full mt-4"
                  onClick={() => setMedicoSeleccionado(medico)}
                >
                  Conóceme
                </Button>
              </CardContent>
            </Card>
          ))}

          {medicosFiltrados.length === 0 && (
            <p className="col-span-full text-center text-sm text-[#94A3B8] py-12">
              No se encontraron médicos con ese criterio.
            </p>
          )}
        </div>
      </div>

      <footer className="border-t border-[#E2E8F0] py-6 text-center text-xs text-[#94A3B8]">
        Impulsado por{' '}
        <Link to="/" className="font-medium text-[#0A4D68] hover:text-[#00354a]">
          Vitalix
        </Link>
      </footer>

      {/* ─────────────────────────────────────────
          MODAL DE DETALLE DEL MÉDICO
          ───────────────────────────────────────── */}
      <Modal
        isOpen={medicoSeleccionado !== null}
        onClose={() => setMedicoSeleccionado(null)}
        title={medicoSeleccionado?.nombre}
        description={medicoSeleccionado?.especialidad || undefined}
      >
        {medicoSeleccionado && (
          <div className="space-y-4">
            {medicoSeleccionado.cedula && (
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-1">
                  Cédula
                </p>
                <p className="flex items-center gap-1.5 text-sm text-[#0B1C30]">
                  <IdCard className="w-4 h-4 text-[#64748B]" /> {medicoSeleccionado.cedula}
                </p>
              </div>
            )}

            {medicoSeleccionado.consultorio && (
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-1">
                  Consultorio
                </p>
                <p className="flex items-center gap-1.5 text-sm text-[#0B1C30]">
                  <MapPin className="w-4 h-4 text-[#64748B]" /> {medicoSeleccionado.consultorio}
                </p>
              </div>
            )}

            <div>
              <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-[#64748B] mb-2">
                <Clock className="w-3.5 h-3.5" /> Horarios de atención
              </p>
              {medicoSeleccionado.horarios.length > 0 ? (
                <div className="space-y-1.5">
                  {agruparHorarios(medicoSeleccionado.horarios).map((linea) => (
                    <div
                      key={linea}
                      className="flex items-center justify-between px-3 py-2 rounded-lg bg-[#F8FAFC] border border-[#E2E8F0] text-sm text-[#0B1C30]"
                    >
                      {linea}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-[#94A3B8]">Horario no publicado todavía.</p>
              )}
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
