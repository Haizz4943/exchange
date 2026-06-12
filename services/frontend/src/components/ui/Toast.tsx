'use client';

import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { clsx } from 'clsx';
import { CheckCircle, XCircle, AlertTriangle, X, Info } from 'lucide-react';

export type ToastVariant = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: string;
  message: string;
  variant: ToastVariant;
  duration?: number;
}

interface ToastContextValue {
  push: (toast: Omit<Toast, 'id'>) => void;
  dismiss: (id: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const variantIcon: Record<ToastVariant, React.ReactNode> = {
  success: <CheckCircle className="hx-h-5 hx-w-5 hx-text-green-500" />,
  error: <XCircle className="hx-h-5 hx-w-5 hx-text-red-500" />,
  warning: <AlertTriangle className="hx-h-5 hx-w-5 hx-text-yellow-500" />,
  info: <Info className="hx-h-5 hx-w-5 hx-text-blue-500" />,
};

let toastIdCounter = 0;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (toast: Omit<Toast, 'id'>) => {
      const id = String(++toastIdCounter);
      const duration = toast.duration ?? 4000;
      setToasts((prev) => [...prev.slice(-4), { ...toast, id }]);
      const timer = setTimeout(() => dismiss(id), duration);
      timers.current.set(id, timer);
    },
    [dismiss],
  );

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((t) => clearTimeout(t));
    };
  }, []);

  return (
    <ToastContext.Provider value={{ push, dismiss }}>
      {children}
      {typeof document !== 'undefined' &&
        createPortal(
          <div
            className="hx-fixed hx-top-4 hx-right-4 hx-z-[9999] hx-flex hx-flex-col hx-gap-2"
            aria-live="polite"
          >
            {toasts.map((t) => (
              <div
                key={t.id}
                className={clsx(
                  'hx-flex hx-items-start hx-gap-3 hx-rounded-lg hx-shadow-lg',
                  'hx-bg-white dark:hx-bg-gray-800 hx-border hx-border-gray-200 dark:hx-border-gray-700',
                  'hx-p-3 hx-min-w-[280px] hx-max-w-[400px]',
                )}
              >
                {variantIcon[t.variant]}
                <p className="hx-flex-1 hx-text-sm hx-text-gray-800 dark:hx-text-gray-100">
                  {t.message}
                </p>
                <button
                  onClick={() => dismiss(t.id)}
                  aria-label="Dismiss notification"
                  className="hx-text-gray-400 hover:hx-text-gray-600"
                >
                  <X className="hx-h-4 hx-w-4" />
                </button>
              </div>
            ))}
          </div>,
          document.body,
        )}
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be inside ToastProvider');
  return ctx;
}
