'use client';

import React from 'react';
import { clsx } from 'clsx';
import { Loader2 } from 'lucide-react';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
}

const variantClasses: Record<NonNullable<ButtonProps['variant']>, string> = {
  primary:
    'hx-bg-blue-600 hx-text-white hover:hx-bg-blue-700 hx-border-transparent',
  secondary:
    'hx-bg-gray-200 hx-text-gray-800 hover:hx-bg-gray-300 hx-border-transparent dark:hx-bg-gray-700 dark:hx-text-gray-100',
  danger:
    'hx-bg-red-600 hx-text-white hover:hx-bg-red-700 hx-border-transparent',
  ghost:
    'hx-bg-transparent hx-text-gray-700 hover:hx-bg-gray-100 hx-border-gray-300 dark:hx-text-gray-300 dark:hover:hx-bg-gray-800',
};

const sizeClasses: Record<NonNullable<ButtonProps['size']>, string> = {
  sm: 'hx-px-3 hx-py-1.5 hx-text-xs',
  md: 'hx-px-4 hx-py-2 hx-text-sm',
  lg: 'hx-px-6 hx-py-3 hx-text-base',
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled,
  children,
  className,
  ...rest
}: ButtonProps) {
  return (
    <button
      disabled={disabled || loading}
      className={clsx(
        'hx-inline-flex hx-items-center hx-justify-center hx-gap-2',
        'hx-rounded hx-border hx-font-medium hx-transition-colors',
        'disabled:hx-opacity-50 disabled:hx-cursor-not-allowed',
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
      {...rest}
    >
      {loading && <Loader2 className="hx-h-4 hx-w-4 hx-animate-spin" />}
      {children}
    </button>
  );
}
