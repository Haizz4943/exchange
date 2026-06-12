'use client';

import React from 'react';
import { clsx } from 'clsx';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => React.ReactNode;
  align?: 'left' | 'right' | 'center';
  className?: string;
}

export interface TableProps<T> {
  columns: Column<T>[];
  rows: T[];
  keyExtractor: (row: T) => string;
  emptyMessage?: string;
  stickyHeader?: boolean;
  className?: string;
}

export function Table<T>({
  columns,
  rows,
  keyExtractor,
  emptyMessage = 'No data available.',
  stickyHeader = false,
  className,
}: TableProps<T>) {
  return (
    <div className={clsx('hx-w-full hx-overflow-auto', className)}>
      <table className="hx-w-full hx-text-sm hx-border-collapse">
        <thead className={clsx(stickyHeader && 'hx-sticky hx-top-0 hx-z-10')}>
          <tr className="hx-border-b hx-border-gray-200 dark:hx-border-gray-700 hx-bg-gray-50 dark:hx-bg-gray-800">
            {columns.map((col) => (
              <th
                key={col.key}
                className={clsx(
                  'hx-px-3 hx-py-2 hx-font-medium hx-text-gray-500 dark:hx-text-gray-400',
                  col.align === 'right' && 'hx-text-right',
                  col.align === 'center' && 'hx-text-center',
                  !col.align && 'hx-text-left',
                  col.className,
                )}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length}
                className="hx-px-3 hx-py-8 hx-text-center hx-text-gray-400"
              >
                {emptyMessage}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr
                key={keyExtractor(row)}
                className="hx-border-b hx-border-gray-100 dark:hx-border-gray-800 hover:hx-bg-gray-50 dark:hover:hx-bg-gray-800/50"
              >
                {columns.map((col) => (
                  <td
                    key={col.key}
                    className={clsx(
                      'hx-px-3 hx-py-2',
                      col.align === 'right' && 'hx-text-right',
                      col.align === 'center' && 'hx-text-center',
                      col.className,
                    )}
                  >
                    {col.render(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
