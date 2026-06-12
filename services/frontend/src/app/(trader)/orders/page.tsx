'use client';

import React from 'react';
import { OpenOrdersTable } from '@/features/orders/components/OpenOrdersTable';
import { OrderHistoryTable } from '@/features/orders/components/OrderHistoryTable';

export default function OrdersPage() {
  return (
    <div>
      <OpenOrdersTable />
      <hr className="hx-border-gray-200 dark:hx-border-gray-800" />
      <OrderHistoryTable />
    </div>
  );
}
