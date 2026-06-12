'use client';

import React from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { Select } from '@/components/ui/Select';

export interface OrderFormProps {
  pair: string;
  side?: 'BUY' | 'SELL';
}

const orderSchema = z
  .object({
    type: z.enum(['LIMIT', 'MARKET']),
    side: z.enum(['BUY', 'SELL']),
    quantity: z
      .string()
      .refine((v) => parseFloat(v) > 0, 'Quantity must be greater than 0'),
    limitPrice: z.string().optional(),
  })
  .refine(
    (d) => d.type === 'MARKET' || (d.limitPrice && parseFloat(d.limitPrice) > 0),
    { message: 'Limit price is required for LIMIT orders', path: ['limitPrice'] },
  );

type OrderFormData = z.infer<typeof orderSchema>;

export function OrderForm({ pair, side: defaultSide = 'BUY' }: OrderFormProps) {
  const {
    register,
    control,
    watch,
    handleSubmit,
    formState: { errors },
  } = useForm<OrderFormData>({
    resolver: zodResolver(orderSchema),
    defaultValues: { type: 'LIMIT', side: defaultSide, quantity: '', limitPrice: '' },
  });

  const orderType = watch('type');

  const onSubmit = (data: OrderFormData) => {
    // STUB: Order Service not yet available
    console.warn('[OrderForm] Order placement stubbed — Order Service not deployed yet:', data);
    alert('Order placement requires the Order Service (not yet available). See docs for status.');
  };

  return (
    <div className="hx-p-3 hx-border hx-border-amber-200 dark:hx-border-amber-800 hx-rounded hx-bg-amber-50 dark:hx-bg-amber-900/10 hx-mb-3">
      <p className="hx-text-xs hx-text-amber-600 dark:hx-text-amber-400 hx-mb-3">
        Order placement requires the Order Service (not yet deployed). Form is wired and validated
        but submit is disabled.
      </p>

      <form onSubmit={handleSubmit(onSubmit)} className="hx-flex hx-flex-col hx-gap-3">
        <Controller
          name="side"
          control={control}
          render={({ field }) => (
            <div className="hx-flex hx-rounded hx-overflow-hidden hx-border hx-border-gray-300 dark:hx-border-gray-700">
              {(['BUY', 'SELL'] as const).map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => field.onChange(s)}
                  className={
                    field.value === s
                      ? s === 'BUY'
                        ? 'hx-flex-1 hx-py-2 hx-text-sm hx-font-medium hx-bg-green-500 hx-text-white'
                        : 'hx-flex-1 hx-py-2 hx-text-sm hx-font-medium hx-bg-red-500 hx-text-white'
                      : 'hx-flex-1 hx-py-2 hx-text-sm hx-text-gray-500 hx-bg-white dark:hx-bg-gray-900 hover:hx-bg-gray-50'
                  }
                >
                  {s}
                </button>
              ))}
            </div>
          )}
        />

        <Controller
          name="type"
          control={control}
          render={({ field }) => (
            <Select
              label="Order type"
              options={[
                { value: 'LIMIT', label: 'Limit' },
                { value: 'MARKET', label: 'Market' },
              ]}
              value={field.value}
              onChange={(e) => field.onChange(e.target.value)}
            />
          )}
        />

        {orderType === 'LIMIT' && (
          <Input
            label="Limit price (USDT)"
            type="number"
            step="0.01"
            min="0"
            placeholder="0.00"
            error={errors.limitPrice?.message}
            {...register('limitPrice')}
          />
        )}

        <Input
          label={`Quantity (${pair.replace('USDT', '')})`}
          type="number"
          step="0.00001"
          min="0"
          placeholder="0.00000"
          error={errors.quantity?.message}
          {...register('quantity')}
        />

        <Button type="submit" variant="primary" className="hx-w-full" disabled>
          Place order (unavailable)
        </Button>
      </form>
    </div>
  );
}
