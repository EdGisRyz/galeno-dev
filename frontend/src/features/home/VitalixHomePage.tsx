import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/auth.store';
import { Button, Card, CardContent } from '@/components/ui';
import {
  Stethoscope,
  Calendar,
  Users,
  FileText,
  Building2,
  ShieldCheck,
  Lock,
  ArrowRight,
} from 'lucide-react';

const FEATURES = [
  {
    icon: Calendar,
    title: 'Agenda y citas médicas',
    description:
      'Programa, confirma y cancela consultas evitando conflictos de horario entre médicos y consultorios.',
  },
  {
    icon: Users,
    title: 'Gestión de pacientes',
    description:
      'Registro, búsqueda y ficha administrativa de pacientes con validación de datos y detección de duplicados.',
  },
  {
    icon: FileText,
    title: 'Expediente clínico digital',
    description:
      'Antecedentes, notas de evolución y diagnósticos con historial cronológico e inmutabilidad de registros finalizados.',
  },
  {
    icon: Building2,
    title: 'Consultorios y personal',
    description:
      'Administra consultorios, médicos y sus horarios de atención, con asignación flexible entre espacios.',
  },
  {
    icon: ShieldCheck,
    title: 'Roles y control de acceso',
    description:
      'Administrador, recepcionista y médico: cada uno ve y opera solo lo que corresponde a su función.',
  },
  {
    icon: Lock,
    title: 'Aislamiento por clínica',
    description:
      'La información de cada clínica es independiente: ningún usuario accede a datos de otra organización.',
  },
];

export function VitalixHomePage() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const navigate = useNavigate();

  const handleRegistroClick = (e: React.MouseEvent) => {
    e.preventDefault();
    alert('El registro público de clínicas estará disponible próximamente.');
  };

  return (
    <div className="min-h-screen bg-[#F8FAFC] text-[#0B1C30]">
      {/* ─────────────────────────────────────────
          ENCABEZADO
          ───────────────────────────────────────── */}
      <header className="sticky top-0 z-20 bg-white/90 backdrop-blur-sm border-b border-[#E2E8F0]">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-[#0A4D68] text-white flex items-center justify-center shadow-xs">
              <Stethoscope className="w-5 h-5 text-[#88BDDC]" />
            </div>
            <span className="font-heading font-bold text-lg tracking-tight text-[#0A4D68]">
              Vitalix
            </span>
          </div>

          <nav className="hidden sm:flex items-center gap-6 text-sm font-medium text-[#41484D]">
            <a href="#funciones" className="hover:text-[#0A4D68] transition-colors">
              Funciones
            </a>
            <a href="#seguridad" className="hover:text-[#0A4D68] transition-colors">
              Seguridad
            </a>
          </nav>

          <div className="flex items-center gap-2">
            {isAuthenticated ? (
              <Button variant="primary" size="sm" onClick={() => navigate('/agenda')}>
                Ir a mi panel
              </Button>
            ) : (
              <>
                <Button variant="outline" size="sm" onClick={() => navigate('/login')}>
                  Iniciar sesión
                </Button>
                <Button variant="primary" size="sm" onClick={handleRegistroClick}>
                  Registrar mi clínica
                </Button>
              </>
            )}
          </div>
        </div>
      </header>

      {/* ─────────────────────────────────────────
          HERO
          ───────────────────────────────────────── */}
      <section className="max-w-6xl mx-auto px-4 sm:px-6 pt-16 pb-20 sm:pt-24 sm:pb-28 text-center">
        <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-[#EFF4FF] text-[#0A4D68] text-xs font-semibold uppercase tracking-wider mb-6">
          Software para clínicas y consultorios médicos
        </span>
        <h1 className="font-heading font-bold text-4xl sm:text-5xl tracking-tight text-[#0B1C30] max-w-3xl mx-auto">
          Administra tu clínica sin complicaciones
        </h1>
        <p className="mt-5 text-base sm:text-lg text-[#41484D] max-w-2xl mx-auto">
          Vitalix centraliza agenda, pacientes y expediente clínico en una sola plataforma, para
          que los consultorios pequeños dejen atrás los registros en papel y las hojas de cálculo
          sueltas.
        </p>
        <div className="mt-8 flex flex-col sm:flex-row items-center justify-center gap-3">
          <Button
            variant="primary"
            size="lg"
            onClick={handleRegistroClick}
            rightIcon={<ArrowRight className="w-4 h-4" />}
          >
            Registrar mi clínica
          </Button>
          <Button variant="outline" size="lg" onClick={() => navigate('/login')}>
            Ya tengo una cuenta
          </Button>
        </div>
      </section>

      {/* ─────────────────────────────────────────
          FUNCIONES
          ───────────────────────────────────────── */}
      <section id="funciones" className="max-w-6xl mx-auto px-4 sm:px-6 py-16 sm:py-20">
        <div className="text-center max-w-2xl mx-auto mb-12">
          <h2 className="font-heading font-bold text-2xl sm:text-3xl tracking-tight text-[#0B1C30]">
            Todo lo que tu consultorio necesita
          </h2>
          <p className="mt-3 text-sm sm:text-base text-[#64748B]">
            Un solo sistema para la parte administrativa y la parte clínica, con el acceso de cada
            persona limitado a lo que le corresponde.
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
          {FEATURES.map((feature) => {
            const Icon = feature.icon;
            return (
              <Card key={feature.title}>
                <CardContent className="p-6">
                  <div className="w-10 h-10 rounded-xl bg-[#EFF4FF] text-[#0A4D68] flex items-center justify-center mb-4">
                    <Icon className="w-5 h-5" />
                  </div>
                  <h3 className="font-heading font-semibold text-base text-[#0B1C30] mb-1.5">
                    {feature.title}
                  </h3>
                  <p className="text-sm text-[#64748B] leading-relaxed">{feature.description}</p>
                </CardContent>
              </Card>
            );
          })}
        </div>
      </section>

      {/* ─────────────────────────────────────────
          SEGURIDAD
          ───────────────────────────────────────── */}
      <section id="seguridad" className="bg-[#0A4D68]">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-16 sm:py-20 text-center">
          <div className="w-12 h-12 rounded-2xl bg-white/10 text-[#88BDDC] flex items-center justify-center mx-auto mb-5">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <h2 className="font-heading font-bold text-2xl sm:text-3xl tracking-tight text-white">
            Pensado para información clínica sensible
          </h2>
          <p className="mt-3 text-sm sm:text-base text-[#BBD8F5] max-w-2xl mx-auto">
            Sesiones con cierre automático por inactividad, acceso a notas clínicas restringido al
            personal médico autorizado, y separación estricta de datos entre clínicas.
          </p>
        </div>
      </section>

      {/* ─────────────────────────────────────────
          PIE DE PÁGINA
          ───────────────────────────────────────── */}
      <footer className="border-t border-[#E2E8F0] bg-white">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-8 flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-[#64748B]">
          <div className="flex items-center gap-2">
            <Stethoscope className="w-4 h-4 text-[#0A4D68]" />
            <span>© {new Date().getFullYear()} Vitalix. Hecho para clínicas y consultorios médicos.</span>
          </div>
          <Link to="/login" className="font-medium text-[#0A4D68] hover:text-[#00354a]">
            Iniciar sesión
          </Link>
        </div>
      </footer>
    </div>
  );
}
