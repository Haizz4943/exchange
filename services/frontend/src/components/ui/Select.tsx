'use client';

import React from 'react';
import { clsx } from 'clsx';

export interface SelectOption {
  value: string;
  label: string;
}

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  options: SelectOption[];
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ label, error, options, className, id, ...rest }, ref) => {
    const selectId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
    return (
      <div className="hx-flex hx-flex-col hx-gap-1">
        {label && (
          <label
            htmlFor={selectId}
            className="hx-text-xs hx-font-medium hx-text-gray-600 dark:hx-text-gray-400"
          >
            {label}
          </label>
        )}
        <select
          id={selectId}
          ref={ref}
          className={clsx(
            'hx-w-full hx-rounded hx-border hx-px-3 hx-py-2 hx-text-sm',
            'hx-bg-white dark:hx-bg-gray-900',
            'hx-text-gray-900 dark:hx-text-gray-100',
            'hx-outline-none focus:hx-ring-2 focus:hx-ring-blue-500',
            error
              ? 'hx-border-red-500'
              : 'hx-border-gray-300 dark:hx-border-gray-700',
            className,
          )}
          {...rest}
        >
          {options.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
        {error && <p className="hx-text-xs hx-text-red-500">{error}</p>}
      </div>
    );
  },
);

Select.displayName = 'Select';
