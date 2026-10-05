import { useState } from 'react';
import { Outlet, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth.store';
import { useInactivityTimeout } from '@/hooks/useInactivityTimeout';
import { InactivityWarningModal } from '@/components/feedback/InactivityWarningModal';
import { Badge } from '@/components/ui';
import {
  Calendar,
  Users,
  FileText,
  CreditCard,
  Settings,
  LogOut,
  Stethoscope,
  Menu,
  X,
  Clock,
  ChevronRight,
  ShieldCheck,
} from 'lucide-react';
import { cn } from '@/lib/utils';

export function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, tenantSlug } = useAuthStore();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  // Activación del temporizador HIPAA de 30 minutos
  useInactivityTimeout();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const navItems = [
    {
      to: '/agenda',
      label: 'Agenda y Citas',
      icon: Calendar,
      description: 'Citas del día y calendario',
    },
    {
      to: '/pacientes',
      label: 'Pacientes',
      icon: Users,
      description: 'Directorio y ficha NOM-004',
    },
    {
      to: '/expediente',
      label: 'Expediente Clínico',
      icon: FileText,
      description: 'Historia clínica y notas SOAP',
    },
    {
      to: '/cobros',
      label: 'Cobros & Caja',
      icon: CreditCard,
      description: 'Ingresos y métodos de pago',
    },
    {
      to: '/administracion',
      label: 'Administración',
      icon: Settings,
      description: 'Médicos, horarios y ajustes',
      adminOnly: true,
    },
  ];

  return (
    <div className="min-h-screen bg-[#F8FAFC] flex flex-col md:flex-row text-[#0B1C30]">
      {/* Modal preventivo de cierre de sesión HIPAA */}
      <InactivityWarningModal />

      {/* ─────────────────────────────────────────
          SIDEBAR CLÍNICA (260px en Desktop)
          ───────────────────────────────────────── */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 w-[260px] bg-white border-r border-[#E2E8F0] flex flex-col transition-transform duration-200 ease-in-out md:translate-x-0',
          isMobileMenuOpen ? 'translate-x-0' : '-translate-x-full'
        )}
      >
        {/* Cabecera de la Clínica */}
        <div className="h-16 px-5 border-b border-[#E2E8F0] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#0A4D68] text-white flex items-center justify-center shadow-xs">
              <Stethoscope className="w-5 h-5 text-[#88BDDC]" />
            </div>
            <div>
              <span className="font-heading font-bold text-sm tracking-tight text-[#0A4D68] block">
                VITALIX SaaS
              </span>
              <span 
  title={`Tenant ID: ${tenantSlug || 'vitalix'}`} 
  className="text-[11px] text-[#64748B] tracking-wide block uppercase font-medium"
>
  Vitalix · Clínica
</span>
            </div>
          </div>
          <button
            onClick={() => setIsMobileMenuOpen(false)}
            className="md:hidden text-[#64748B] hover:text-[#0B1C30] p-1"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Navegación Modular */}
        <div className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
          <div className="px-3 pb-2 text-[10px] font-bold uppercase tracking-wider text-[#94A3B8]">
            Módulos Clínicos
          </div>
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive =
              location.pathname === item.to ||
              (item.to === '/agenda' && location.pathname === '/');

            return (
              <NavLink
                key={item.to}
                to={item.to}
                onClick={() => setIsMobileMenuOpen(false)}
                className={cn(
                  'flex items-center justify-between px-3 py-2.5 rounded-xl text-xs font-medium transition-all group select-none',
                  isActive
                    ? 'bg-[#E5EEFF] text-[#0A4D68] font-semibold shadow-2xs'
                    : 'text-[#41484D] hover:bg-[#F1F5F9] hover:text-[#0B1C30]'
                )}
              >
                <div className="flex items-center gap-3">
                  <Icon
                    className={cn(
                      'w-4 h-4 transition-colors',
                      isActive ? 'text-[#0A4D68]' : 'text-[#64748B] group-hover:text-[#0A4D68]'
                    )}
                  />
                  <span>{item.label}</span>
                </div>
                {isActive && <ChevronRight className="w-3.5 h-3.5 text-[#0A4D68]" />}
              </NavLink>
            );
          })}
        </div>

        {/* Perfil del Usuario Activo */}
        <div className="p-3 border-t border-[#E2E8F0] bg-[#F8FAFC]/60">
          <div className="p-2.5 rounded-xl bg-white border border-[#E2E8F0] shadow-2xs">
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-full bg-[#E5EEFF] text-[#0A4D68] font-bold text-xs flex items-center justify-center">
                  {user?.email?.charAt(0).toUpperCase() || 'U'}
                </div>
                <div className="overflow-hidden">
                  <p className="text-xs font-semibold text-[#0B1C30] truncate">
                    {user?.nombre || user?.email?.split('@')[0] || 'Médico'}
                  </p>
                  <p className="text-[10px] text-[#64748B] truncate">
                    {user?.cedula ? `Céd. ${user.cedula}` : user?.email}
                  </p>
                </div>
              </div>
            </div>
            <div className="flex items-center justify-between pt-1 border-t border-[#F1F5F9]">
              <Badge variant="primary" showDot={false}>
                {user?.role || 'MEDICO'}
              </Badge>
              <button
                onClick={handleLogout}
                className="text-[11px] font-medium text-[#E11D48] hover:text-[#be123c] flex items-center gap-1 transition-colors cursor-pointer"
                title="Cerrar sesión segura"
              >
                <LogOut className="w-3 h-3" />
                <span>Salir</span>
              </button>
            </div>
          </div>
        </div>
      </aside>

      {/* Backdrop móvil */}
      {isMobileMenuOpen && (
        <div
          className="fixed inset-0 z-30 bg-slate-900/40 backdrop-blur-2xs md:hidden"
          onClick={() => setIsMobileMenuOpen(false)}
        />
      )}

      {/* ─────────────────────────────────────────
          CONTENEDOR PRINCIPAL
          ───────────────────────────────────────── */}
      <div className="flex-1 md:pl-[260px] flex flex-col min-w-0">
        {/* Header Superior Clínico */}
        <header className="h-16 px-4 sm:px-6 bg-white border-b border-[#E2E8F0] flex items-center justify-between sticky top-0 z-20">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsMobileMenuOpen(true)}
              className="md:hidden p-1.5 rounded-lg border border-[#E2E8F0] text-[#64748B] hover:bg-[#F8FAFC]"
            >
              <Menu className="w-5 h-5" />
            </button>
            <div className="flex items-center gap-2">
              <span className="font-heading font-semibold text-sm sm:text-base text-[#0B1C30]">
                Clínica 
              </span>
              <span className="text-[#94A3B8] hidden sm:inline">/</span>
              <span className="text-xs text-[#64748B] hidden sm:inline font-medium">
                Piso 1 · Consultorios 101-102
              </span>
            </div>
          </div>

          {/* Indicador de Seguridad HIPAA y Estado */}
          <div className="flex items-center gap-2 sm:gap-4">
            <div className="hidden lg:flex items-center gap-2 px-3 py-1 bg-[#F8FAFC] border border-[#E2E8F0] rounded-full text-xs text-[#41484D]">
              <Clock className="w-3.5 h-3.5 text-[#0A4D68]" />
              <span className="tnum">
                {new Date().toLocaleDateString('es-MX', {
                  weekday: 'short',
                  day: 'numeric',
                  month: 'short',
                })}
              </span>
            </div>

            <div className="flex items-center gap-1.5 px-2.5 py-1 bg-[#ECFDF5] border border-[#A7F3D0] rounded-full text-[11px] font-semibold text-[#047857]">
              <ShieldCheck className="w-3.5 h-3.5 text-[#059669]" />
              <span className="hidden sm:inline">HIPAA · Sesión Segura</span>
              <span className="sm:hidden">HIPAA</span>
            </div>
          </div>
        </header>

        {/* Canvas de Contenido */}
        <main className="flex-1 p-4 sm:p-6 lg:p-8 max-w-7xl w-full mx-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
}