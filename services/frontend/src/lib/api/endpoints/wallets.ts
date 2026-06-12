import { ApiClient } from '../client';

export interface WalletBalance {
  walletId: string;
  assetCode: string;
  total: string;
  available: string;
  frozen: string;
}

export interface WalletsResponse {
  wallets: WalletBalance[];
}

export interface WalletTransaction {
  txnId: string;
  walletId: string;
  assetCode: string;
  type: string;
  amount: string;
  balanceBefore: string;
  balanceAfter: string;
  referenceId: string | null;
  referenceType: string | null;
  createdAt: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface DepositRequest {
  assetCode: string;
  amount: string;
  clientRequestId: string;
}

export interface DepositResponse {
  depositId: string;
  assetCode: string;
  amount: string;
  status: string;
  confirmedAt: string;
}

export interface WithdrawalRequest {
  assetCode: string;
  amount: string;
  clientRequestId: string;
}

export interface WithdrawalResponse {
  withdrawalId: string;
  assetCode: string;
  amount: string;
  status: string;
  confirmedAt: string;
}

export function walletsApi(client: ApiClient) {
  return {
    getMyWallets: () =>
      client.get<WalletsResponse>('/api/v1/wallets/me'),

    getTransactions: (params?: { asset?: string; type?: string; page?: number; size?: number }) =>
      client.get<PageResponse<WalletTransaction>>('/api/v1/wallet-transactions', params),

    deposit: (req: DepositRequest) =>
      client.post<DepositResponse>('/api/v1/deposits', req),

    getDeposits: (params?: { page?: number; size?: number }) =>
      client.get<PageResponse<DepositResponse>>('/api/v1/deposits', params),

    withdraw: (req: WithdrawalRequest) =>
      client.post<WithdrawalResponse>('/api/v1/withdrawals', req),

    getWithdrawals: (params?: { page?: number; size?: number }) =>
      client.get<PageResponse<WithdrawalResponse>>('/api/v1/withdrawals', params),
  };
}
