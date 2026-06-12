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

const depositSchema = z.object({
  assetCode: z.literal('USDT'),
  amount: z
    .string()
    .refine((v) => parseFloat(v) > 0 && parseFloat(v) <= 100_000, {
      message: 'Amount must be between 0 and 100,000 USDT',
    }),
});

type DepositFormData = z.infer<typeof depositSchema>;

export function DepositDialog() {
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
  } = useForm<DepositFormData>({
    resolver: zodResolver(depositSchema),
    defaultValues: { assetCode: 'USDT', amount: '' },
  });

  const onSubmit = async (data: DepositFormData) => {
    setLoading(true);
    setError(null);
    setSuccess(null);
    try {
      const res = await walletsApi(apiClient).deposit({
        assetCode: data.assetCode,
        amount: data.amount,
        clientRequestId: uuidv4(),
      });
      setSuccess(`Deposit of ${res.amount} USDT confirmed!`);
      reset();
      await queryClient.invalidateQueries({ queryKey: ['wallets', 'me'] });
    } catch (err) {
      const msg = err instanceof ApiError ? err.userMessage : 'Deposit failed. Please try again.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="hx-p-6 hx-max-w-md">
      <h2 className="hx-text-xl hx-font-semibold hx-mb-2 hx-text-gray-900 dark:hx-text-white">
        Deposit USDT
      </h2>
      <p className="hx-text-sm hx-text-gray-500 hx-mb-6">
        Simulated deposit — instantly confirmed. Maximum 100,000 USDT per request.
      </p>

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
        <Input
          label="Asset"
          value="USDT"
          disabled
          hint="Only USDT deposits are supported in MVP"
          {...register('assetCode')}
        />

        <Input
          label="Amount (USDT)"
          type="number"
          step="0.01"
          min="0.01"
          max="100000"
          placeholder="1000.00"
          error={errors.amount?.message}
          {...register('amount')}
        />

        <Button type="submit" loading={loading} className="hx-w-full">
          Deposit
        </Button>
      </form>
    </div>
  );
}
