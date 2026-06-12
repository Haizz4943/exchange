'use client';

import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { clsx } from 'clsx';
import { X } from 'lucide-react';

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  className?: string;
}

export function Modal({ open, onClose, title, children, className }: ModalProps) {
  const portalRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    // SSR-safe portal target
    portalRef.current = document.body;
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open || typeof document === 'undefined') return null;

  return createPortal(
    <div
      className="hx-fixed hx-inset-0 hx-z-50 hx-flex hx-items-center hx-justify-center hx-bg-black/50"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        className={clsx(
          'hx-relative hx-w-full hx-max-w-md hx-rounded-lg hx-bg-white dark:hx-bg-gray-900',
          'hx-p-6 hx-shadow-xl',
          className,
        )}
        role="dialog"
        aria-modal="true"
      >
        {title && (
          <div className="hx-flex hx-items-center hx-justify-between hx-mb-4">
            <h2 className="hx-text-lg hx-font-semibold">{title}</h2>
            <button
              onClick={onClose}
              aria-label="Close dialog"
              className="hx-rounded hx-p-1 hover:hx-bg-gray-100 dark:hover:hx-bg-gray-800"
            >
              <X className="hx-h-5 hx-w-5" />
            </button>
          </div>
        )}
        {children}
      </div>
    </div>,
    document.body,
  );
}
