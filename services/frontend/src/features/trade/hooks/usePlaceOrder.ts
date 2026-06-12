'use client';

/**
 * usePlaceOrder — stub hook.
 * Order Service is not yet deployed.
 * When available: wire to ordersApi(apiClient).place() + invalidate order list query.
 */
export function usePlaceOrder() {
  return {
    placeOrder: async () => {
      throw new Error('Order Service not yet available');
    },
    loading: false,
    error: null as string | null,
  };
}
