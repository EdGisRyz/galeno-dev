import React from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'outline' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant = 'primary',
      size = 'md',
      isLoading = false,
      leftIcon,
      rightIcon,
      disabled,
      children,
      ...props
    },
    ref
  ) => {
    // Clinical Precision & Care button specifications:
    // Radius 8px (rounded-lg), heights 36px/40px/44px, focus ring 2px #06B6D4
    const baseStyles =
      'inline-flex items-center justify-center font-medium rounded-lg transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-[#06B6D4] focus:ring-offset-2 disabled:opacity-50 disabled:pointer-events-none cursor-pointer select-none';

    const variants = {
      primary:
        'bg-[#0A4D68] text-white hover:bg-[#00354a] active:bg-[#002433] shadow-xs hover:shadow-sm',
      secondary:
        'bg-white text-[#0A4D68] border border-[#E2E8F0] hover:bg-[#F1F5F9] active:bg-[#E2E8F0] shadow-2xs',
      danger:
        'bg-[#E11D48] text-white hover:bg-[#be123c] active:bg-[#9f1239] shadow-xs',
      outline:
        'border border-[#CBD5E1] bg-white text-[#0B1C30] hover:bg-[#F8FAFC] active:bg-[#F1F5F9]',
      ghost:
        'text-[#0A4D68] hover:bg-[#EFF4FF] hover:text-[#00354a] active:bg-[#E5EEFF]',
    };

    const sizes = {
      sm: 'h-8 text-xs px-3 gap-1.5',
      md: 'h-10 text-sm px-4 gap-2',
      lg: 'h-11 text-base px-5 gap-2.5',
    };

    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={cn(baseStyles, variants[variant], sizes[size], className)}
        {...props}
      >
        {isLoading ? (
          <Loader2 className="w-4 h-4 animate-spin text-current" />
        ) : (
          leftIcon && <span className="inline-flex shrink-0">{leftIcon}</span>
        )}
        <span>{children}</span>
        {!isLoading && rightIcon && (
          <span className="inline-flex shrink-0">{rightIcon}</span>
        )}
      </button>
    );
  }
);

Button.displayName = 'Button';

