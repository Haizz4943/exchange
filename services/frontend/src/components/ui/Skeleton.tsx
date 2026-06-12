'use client';

import React from 'react';
import { clsx } from 'clsx';

export interface SkeletonProps {
  className?: string;
  height?: string | number;
  width?: string | number;
  rounded?: boolean;
}

export function Skeleton({ className, height, width, rounded = false }: SkeletonProps) {
  return (
    <div
      className={clsx(
        'hx-animate-pulse hx-bg-gray-200 dark:hx-bg-gray-700',
        rounded ? 'hx-rounded-full' : 'hx-rounded',
        className,
      )}
      style={{ height, width }}
      aria-hidden="true"
    />
  );
}

/** Stack of skeleton rows for table loading states */
export function SkeletonRows({ count = 5, columns = 4 }: { count?: number; columns?: number }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <tr key={i} className="hx-border-b hx-border-gray-100 dark:hx-border-gray-800">
          {Array.from({ length: columns }).map((__, j) => (
            <td key={j} className="hx-px-3 hx-py-2">
              <Skeleton height={16} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
