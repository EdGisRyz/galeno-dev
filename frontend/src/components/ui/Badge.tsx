import React from 'react';
import { cn } from '@/lib/utils';
import type { AppointmentStatus, PaymentStatus } from '@/types/models';

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?:
    | 'default'
    | 'primary'
    | 'secondary'
    | 'success'
    | 'warning'
    | 'danger'
    | 'outline'
    | AppointmentStatus
    | PaymentStatus;
  showDot?: boolean;
}

export function Badge({
  className,
  variant = 'default',
  showDot = true,
  children,
  ...props
}: BadgeProps) {
  // Clinical Precision & Care chip/badge specification:
  // Pill-shaped (rounded-full), semi-bold 11px uppercase, 4px/10px padding, 6px dot indicator
  const config: Record<string, { bg: string; text: string; border: string; dot: string; label: string }> = {
    // Medical Triage & Citas
    CONFIRMADA: {
      bg: 'bg-[#ECFDF5]',
      text: 'text-[#047857]',
      border: 'border-[#A7F3D0]',
      dot: 'bg-[#059669]',
      label: 'Confirmada',
    },
    SIN_CONFIRMAR: {
      bg: 'bg-[#FFFBEB]',
      text: 'text-[#B45309]',
      border: 'border-[#FDE68A]',
      dot: 'bg-[#D97706]',
      label: 'Sin Confirmar',
    },
    CANCELADA: {
      bg: 'bg-[#FFF1F2]',
      text: 'text-[#BE123C]',
      border: 'border-[#FECDD3]',
      dot: 'bg-[#E11D48]',
      label: 'Cancelada',
    },
    REAGENDADA: {
      bg: 'bg-[#EEF2FF]',
      text: 'text-[#4338CA]',
      border: 'border-[#C7D2FE]',
      dot: 'bg-[#4F46E5]',
      label: 'Reagendada',
    },
    NO_ASISTIO: {
      bg: 'bg-[#F5F3FF]',
      text: 'text-[#6D28D9]',
      border: 'border-[#DDD6FE]',
      dot: 'bg-[#7C3AED]',
      label: 'No Asistió',
    },

    // Pagos
    PAGADO: {
      bg: 'bg-[#ECFDF5]',
      text: 'text-[#065F46]',
      border: 'border-[#A7F3D0]',
      dot: 'bg-[#059669]',
      label: 'Pagado',
    },
    PENDIENTE: {
      bg: 'bg-[#FFF7ED]',
      text: 'text-[#C2410C]',
      border: 'border-[#FFEDD5]',
      dot: 'bg-[#EA580C]',
      label: 'Pendiente',
    },

    // Roles & Estilos Generales
    default: {
      bg: 'bg-[#F1F5F9]',
      text: 'text-[#475569]',
      border: 'border-[#CBD5E1]',
      dot: 'bg-[#94A3B8]',
      label: 'Info',
    },
    primary: {
      bg: 'bg-[#E5EEFF]',
      text: 'text-[#0A4D68]',
      border: 'border-[#BBD8F5]',
      dot: 'bg-[#0A4D68]',
      label: 'Primario',
    },
    secondary: {
      bg: 'bg-[#E6FFFA]',
      text: 'text-[#088395]',
      border: 'border-[#B2F5EA]',
      dot: 'bg-[#088395]',
      label: 'Secundario',
    },
    success: {
      bg: 'bg-[#ECFDF5]',
      text: 'text-[#047857]',
      border: 'border-[#A7F3D0]',
      dot: 'bg-[#059669]',
      label: 'Éxito',
    },
    warning: {
      bg: 'bg-[#FFFBEB]',
      text: 'text-[#B45309]',
      border: 'border-[#FDE68A]',
      dot: 'bg-[#D97706]',
      label: 'Atención',
    },
    danger: {
      bg: 'bg-[#FFF1F2]',
      text: 'text-[#BE123C]',
      border: 'border-[#FECDD3]',
      dot: 'bg-[#E11D48]',
      label: 'Crítico',
    },
    outline: {
      bg: 'bg-transparent',
      text: 'text-[#0B1C30]',
      border: 'border-[#CBD5E1]',
      dot: 'bg-[#0A4D68]',
      label: '',
    },
  };

  const item = config[variant] || config.default;
  const displayText = children || item.label;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold tracking-wider uppercase border transition-colors select-none',
        item.bg,
        item.text,
        item.border,
        className
      )}
      {...props}
    >
      {showDot && <span className={cn('w-1.5 h-1.5 rounded-full shrink-0', item.dot)} />}
      <span>{displayText}</span>
    </span>
  );
}

