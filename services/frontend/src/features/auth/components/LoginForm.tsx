'use client';

import React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import Link from 'next/link';
import { useLogin } from '../hooks/useLogin';
import { useNavigation } from '@/lib/navigation/useNavigation';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

const loginSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
});

type LoginFormData = z.infer<typeof loginSchema>;

export function LoginForm() {
  const { login, loading, error } = useLogin();
  const { navigate } = useNavigation();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormData>({ resolver: zodResolver(loginSchema) });

  const onSubmit = async (data: LoginFormData) => {
    try {
      await login(data);
      navigate({ screen: 'trade', pair: process.env.NEXT_PUBLIC_DEFAULT_PAIR ?? 'BTCUSDT' });
    } catch {
      // Error already set in useLogin
    }
  };

  return (
    <div className="hx-flex hx-min-h-screen hx-items-center hx-justify-center hx-bg-gray-50 dark:hx-bg-gray-950 hx-p-4">
      <div className="hx-w-full hx-max-w-sm">
        <div className="hx-mb-8 hx-text-center">
          <h1 className="hx-text-2xl hx-font-bold hx-text-gray-900 dark:hx-text-white">
            Sign in to Haizz
          </h1>
          <p className="hx-mt-2 hx-text-sm hx-text-gray-500">
            Trade crypto with simulated funds
          </p>
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
            autoComplete="current-password"
            error={errors.password?.message}
            {...register('password')}
          />

          <Button type="submit" loading={loading} className="hx-w-full hx-mt-2">
            Sign in
          </Button>

          <p className="hx-text-center hx-text-sm hx-text-gray-500">
            No account?{' '}
            <Link
              href="/register"
              className="hx-text-blue-600 hover:hx-underline"
            >
              Create one
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}
