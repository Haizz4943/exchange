'use client';

import React, { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { v4 as uuidv4 } from 'uuid';
import { useQueryClient } from '@tanstack/react-query';
import { walletsApi } from '@/lib/api/endpoints/wallets';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { ApiError } from '@/lib/api/errors';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { Select } from '@/components/ui/Select';

const withdrawSchema = z.object({
  assetCode: z.string().min(1),
  amount: z
    .string()
    .refine((v) => parseFloat(v) > 0, { message: 'Amount must be greater than 0' }),
});

type WithdrawFormData = z.infer<typeof withdrawSchema>;

const ASSET_OPTIONS = [
  { value: 'USDT', label: 'USDT' },
  { value: 'BTC', label: 'BTC' },
  { value: 'ETH', label: 'ETH' },
  { value: 'BNB', label: 'BNB' },
  { value: 'SOL', label: 'SOL' },
  { value: 'XRP', label: 'XRP' },
];

export function WithdrawDialog() {
  const { apiClient } = useAuthContext();
  const queryClient = useQueryClient();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<WithdrawFormData>({
    resolver: zodResolver(withdrawSchema),
    defaultValues: { assetCode: 'USDT', amount: '' },
  });

  const onSubmit = async (data: WithdrawFormData) => {
    setLoading(true);
    setError(null);
    setSuccess(null);
    try {
      const res = await walletsApi(apiClient).withdraw({
        assetCode: data.assetCode,
        amount: data.amount,
        clientRequestId: uuidv4(),
      });
      setSuccess(`Withdrawal of ${res.amount} ${res.assetCode} confirmed!`);
      reset();
      await queryClient.invalidateQueries({ queryKey: ['wallets', 'me'] });
    } catch (err) {
      const msg = err instanceof ApiError ? err.userMessage : 'Withdrawal failed.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="hx-p-6 hx-max-w-md">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-6">Withdraw</h2>

      {success && (
        <div className="hx-rounded hx-bg-green-50 dark:hx-bg-green-900/20 hx-border hx-border-green-200 dark:hx-border-green-800 hx-p-3 hx-text-sm hx-text-green-700 dark:hx-text-green-400 hx-mb-4">
          {success}
        </div>
      )}
      {error && (
        <div className="hx-rounded hx-bg-red-50 dark:hx-bg-red-900/20 hx-border hx-border-red-200 dark:hx-border-red-800 hx-p-3 hx-text-sm hx-text-red-700 dark:hx-text-red-400 hx-mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit(onSubmit)} className="hx-flex hx-flex-col hx-gap-4">
        <Select
          label="Asset"
          options={ASSET_OPTIONS}
          {...register('assetCode')}
        />
        <Input
          label="Amount"
          type="number"
          step="0.00000001"
          min="0.00000001"
          error={errors.amount?.message}
          {...register('amount')}
        />
        <Button type="submit" loading={loading} className="hx-w-full">
          Withdraw
        </Button>
      </form>
    </div>
  );
}
