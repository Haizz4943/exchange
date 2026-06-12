'use client';

import React, { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import Link from 'next/link';
import { authApi } from '@/lib/api/endpoints/auth';
import { useAuthContext } from '@/lib/auth/AuthContext';
import { useNavigation } from '@/lib/navigation/useNavigation';
import { ApiError } from '@/lib/api/errors';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

const registerSchema = z
  .object({
    email: z.string().email('Please enter a valid email address'),
    password: z
      .string()
      .min(8, 'Password must be at least 8 characters')
      .regex(/[A-Z]/, 'Password must contain at least one uppercase letter')
      .regex(/[0-9]/, 'Password must contain at least one digit'),
    confirmPassword: z.string(),
  })
  .refine((d) => d.password === d.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

type RegisterFormData = z.infer<typeof registerSchema>;

export function RegisterForm() {
  const { apiClient } = useAuthContext();
  const { navigate } = useNavigation();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormData>({ resolver: zodResolver(registerSchema) });

  const onSubmit = async (data: RegisterFormData) => {
    setLoading(true);
    setError(null);
    try {
      await authApi(apiClient).register({ email: data.email, password: data.password });
      setSuccess(true);
    } catch (err) {
      const msg =
        err instanceof ApiError ? err.userMessage : 'Registration failed. Please try again.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div className="hx-flex hx-min-h-screen hx-items-center hx-justify-center hx-p-4">
        <div className="hx-w-full hx-max-w-sm hx-text-center">
          <div className="hx-text-green-500 hx-text-5xl hx-mb-4">✓</div>
          <h2 className="hx-text-xl hx-font-bold hx-mb-2">Account created!</h2>
          <p className="hx-text-sm hx-text-gray-500 hx-mb-6">
            Your wallets are being provisioned. Sign in to start trading.
          </p>
          <Button onClick={() => navigate({ screen: 'login' })} className="hx-w-full">
            Go to login
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="hx-flex hx-min-h-screen hx-items-center hx-justify-center hx-bg-gray-50 dark:hx-bg-gray-950 hx-p-4">
      <div className="hx-w-full hx-max-w-sm">
        <div className="hx-mb-8 hx-text-center">
          <h1 className="hx-text-2xl hx-font-bold hx-text-gray-900 dark:hx-text-white">
            Create an account
          </h1>
          <p className="hx-mt-2 hx-text-sm hx-text-gray-500">Start with 10,000 USDT of simulated funds</p>
        </div>

        <form
          onSubmit={handleSubmit(onSubmit)}
          className="hx-flex hx-flex-col hx-gap-4 hx-bg-white dark:hx-bg-gray-900 hx-rounded-lg hx-p-6 hx-shadow"
          noValidate
        >
          {error && (
            <div className="hx-rounded hx-bg-red-50 dark:hx-bg-red-900/20 hx-border hx-border-red-200 dark:hx-border-red-800 hx-p-3 hx-text-sm hx-text-red-700 dark:hx-text-red-400">
              {error}
            </div>
          )}

          <Input
            label="Email"
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register('email')}
          />

          <Input
            label="Password"
            type="password"
            autoComplete="new-password"
            hint="Min 8 chars, 1 uppercase, 1 digit"
            error={errors.password?.message}
            {...register('password')}
          />

          <Input
            label="Confirm password"
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            {...register('confirmPassword')}
          />

          <Button type="submit" loading={loading} className="hx-w-full hx-mt-2">
            Create account
          </Button>

          <p className="hx-text-center hx-text-sm hx-text-gray-500">
            Already have an account?{' '}
            <Link href="/login" className="hx-text-blue-600 hover:hx-underline">
              Sign in
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
