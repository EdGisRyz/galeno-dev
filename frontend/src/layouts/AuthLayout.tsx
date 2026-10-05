import { Outlet } from 'react-router-dom';
import { Stethoscope, ShieldCheck, CheckCircle2 } from 'lucide-react';

export function AuthLayout() {
  return (
    <div className="min-h-screen bg-[#F8FAFC] flex flex-col justify-center py-12 px-4 sm:px-6 lg:px-8">
      {/* Cabecera institucional */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center mb-6">
        <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-[#0A4D68] text-white shadow-sm mb-3">
          <Stethoscope className="w-6 h-6 text-[#88BDDC]" />
        </div>
        <h2 className="text-2xl font-bold font-heading text-[#0B1C30] tracking-tight">
          Galeno SaaS
        </h2>
        <p className="mt-1 text-xs text-[#64748B]">
          Sistema de Gestión Clínica y Expediente Electrónico NOM-004
        </p>
      </div>

      {/* Contenedor del Formulario */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white py-8 px-6 sm:px-10 rounded-2xl border border-[#E2E8F0] shadow-[0_1px_3px_0_rgba(15,23,42,0.05),0_1px_2px_-1px_rgba(15,23,42,0.05)]">
          <Outlet />
        </div>

        {/* Garantías de Seguridad & Normativa al pie */}
        <div className="mt-6 flex items-center justify-center gap-6 text-[11px] text-[#64748B]">
          <span className="flex items-center gap-1.5">
            <ShieldCheck className="w-3.5 h-3.5 text-[#059669]" />
            NOM-004 / NOM-024
          </span>
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-3.5 h-3.5 text-[#0A4D68]" />
            Cifrado RS256 / HIPAA
          </span>
          <span>InToGlobe SaaS</span>
        </div>
      </div>
    </div>
  );
}