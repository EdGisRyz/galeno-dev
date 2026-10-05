import React from 'react';
import { AlertCircle, CheckCircle2, Info, AlertTriangle } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface AlertProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'info' | 'success' | 'warning' | 'error';
  title?: string;
  icon?: boolean;
}

export function Alert({
  className,
  variant = 'info',
  title,
  icon = true,
  children,
  ...props
}: AlertProps) {
  const styles = {
    info: 'bg-[#EFF4FF] text-[#0A4D68] border-[#BFDBFE] [&>svg]:text-[#0A4D68]',
    success: 'bg-[#ECFDF5] text-[#065F46] border-[#A7F3D0] [&>svg]:text-[#059669]',
    warning: 'bg-[#FFFBEB] text-[#92400E] border-[#FDE68A] [&>svg]:text-[#D97706]',
    error: 'bg-[#FFF1F2] text-[#9F1239] border-[#FECDD3] [&>svg]:text-[#E11D48]',
  };

  const Icons = {
    info: Info,
    success: CheckCircle2,
    warning: AlertTriangle,
    error: AlertCircle,
  };

  const IconComponent = Icons[variant];

  return (
    <div
      role="alert"
      className={cn(
        'relative w-full rounded-xl border p-4 text-xs sm:text-sm flex gap-3 shadow-2xs',
        styles[variant],
        className
      )}
      {...props}
    >
      {icon && <IconComponent className="w-5 h-5 shrink-0 mt-0.5" />}
      <div className="flex-1">
        {title && <h5 className="font-semibold leading-tight tracking-tight mb-1">{title}</h5>}
        <div className="leading-relaxed opacity-95">{children}</div>
      </div>
    </div>
  );
}

