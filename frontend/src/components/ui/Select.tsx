import React from 'react';
import { ChevronDown } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface SelectOption {
  value: string | number;
  label: string;
}

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  helperText?: string;
  options: SelectOption[];
  placeholder?: string;
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, label, error, helperText, options, placeholder, id, ...props }, ref) => {
    const selectId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined);

    return (
      <div className="w-full">
        {label && (
          <label
            htmlFor={selectId}
            className="block text-xs font-semibold text-[#0B1C30] uppercase tracking-wider mb-1.5"
          >
            {label}
            {props.required && <span className="text-[#E11D48] ml-1">*</span>}
          </label>
        )}
        <div className="relative rounded-lg shadow-2xs">
          <select
            id={selectId}
            ref={ref}
            className={cn(
              'block w-full h-[38px] appearance-none rounded-lg border border-[#CBD5E1] bg-white px-3.5 pr-10 text-sm text-[#0B1C30] focus:outline-none focus:border-[#088395] focus:ring-3 focus:ring-[#06B6D4]/15 transition-all duration-150 disabled:bg-[#F8FAFC] disabled:text-[#94A3B8] cursor-pointer',
              error && 'border-[#E11D48] text-[#9F1239] focus:border-[#E11D48] focus:ring-3 focus:ring-[#E11D48]/15',
              className
            )}
            {...props}
          >
            {placeholder && (
              <option value="" disabled>
                {placeholder}
              </option>
            )}
            {options.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
          <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3 text-[#64748B]">
            <ChevronDown className="w-4 h-4" />
          </div>
        </div>
        {error && <p className="mt-1.5 text-xs text-[#E11D48] font-medium">{error}</p>}
        {helperText && !error && <p className="mt-1 text-xs text-[#64748B]">{helperText}</p>}
      </div>
    );
  }
);

Select.displayName = 'Select';

