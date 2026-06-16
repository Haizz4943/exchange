'use client';

import React from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { Select } from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { ordersApi, type PlaceOrderRequest } from '@/lib/api/endpoints/orders';
import { ApiError } from '@/lib/api/errors';
import { generateCorrelationId } from '@/lib/utils/correlationId';

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
  const { apiClient } = useAuthContext();
  const queryClient = useQueryClient();
  const { push } = useToast();

  const {
    register,
    control,
    watch,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<OrderFormData>({
    resolver: zodResolver(orderSchema),
    defaultValues: { type: 'LIMIT', side: defaultSide, quantity: '', limitPrice: '' },
  });

  const orderType = watch('type');

  const base = pair.replace('USDT', '');

  const mutation = useMutation({
    mutationFn: (req: PlaceOrderRequest) => ordersApi(apiClient).place(req),
    onSuccess: (_data, req) => {
      push({
        message: `Đã đặt lệnh ${req.side} ${req.quantity} ${base}`,
        variant: 'success',
      });
      queryClient.invalidateQueries({ queryKey: ['orders'] });
      queryClient.invalidateQueries({ queryKey: ['wallets'] });
      // Keep side/type, clear the entered amounts.
      reset({ type: req.type, side: req.side, quantity: '', limitPrice: '' });
    },
    onError: (err: unknown) => {
      const message =
        err instanceof ApiError
          ? err.userMessage
          : err instanceof Error
            ? err.message
            : 'Đặt lệnh thất bại';
      push({ message, variant: 'error' });
    },
  });

  const onSubmit = (data: OrderFormData) => {
    const req: PlaceOrderRequest = {
      client_order_id: generateCorrelationId(),
      pair,
      side: data.side,
      type: data.type,
      quantity: data.quantity,
      time_in_force: 'GTC',
      // Only send limit_price for LIMIT orders — backend rejects it for MARKET.
      ...(data.type === 'LIMIT' ? { limit_price: data.limitPrice } : {}),
    };
    mutation.mutate(req);
  };

  const side = watch('side');

  return (
    <div className="hx-p-3">
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
          label={`Quantity (${base})`}
          type="number"
          step="0.00001"
          min="0"
          placeholder="0.00000"
          error={errors.quantity?.message}
          {...register('quantity')}
        />

        <Button
          type="submit"
          variant="primary"
          className="hx-w-full"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? 'Đang đặt...' : `Đặt lệnh ${side}`}
        </Button>
      </form>
    </div>
  );
}
