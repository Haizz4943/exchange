'use client';

import React from 'react';
import { clsx } from 'clsx';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  hint?: string;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ label, error, hint, className, id, ...rest }, ref) => {
    const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
    return (
      <div className="hx-flex hx-flex-col hx-gap-1">
        {label && (
          <label
            htmlFor={inputId}
            className="hx-text-xs hx-font-medium hx-text-gray-600 dark:hx-text-gray-400"
          >
            {label}
          </label>
        )}
        <input
          id={inputId}
          ref={ref}
          className={clsx(
            'hx-w-full hx-rounded hx-border hx-px-3 hx-py-2 hx-text-sm',
            'hx-bg-white dark:hx-bg-gray-900',
            'hx-text-gray-900 dark:hx-text-gray-100',
            'hx-outline-none focus:hx-ring-2 focus:hx-ring-blue-500',
            error
              ? 'hx-border-red-500 focus:hx-ring-red-500'
              : 'hx-border-gray-300 dark:hx-border-gray-700',
            className,
          )}
          {...rest}
        />
        {error && <p className="hx-text-xs hx-text-red-500">{error}</p>}
        {!error && hint && (
          <p className="hx-text-xs hx-text-gray-400">{hint}</p>
        )}
      </div>
    );
  },
);

Input.displayName = 'Input';
