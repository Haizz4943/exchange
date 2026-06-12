import { ApiClient } from '../client';

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface RegisterResponse {
  user_id: string;
  email: string;
  created_at: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  user: {
    user_id: string;
    email: string;
  };
}

export interface RefreshRequest {
  refresh_token: string;
}

export interface LogoutRequest {
  refresh_token: string;
}

export interface MeResponse {
  user_id: string;
  email: string;
  external_provider: string;
  status: string;
}

export function authApi(client: ApiClient) {
  return {
    register: (req: RegisterRequest) =>
      client.post<RegisterResponse>('/api/v1/auth/register', req),

    login: (req: LoginRequest) =>
      client.post<LoginResponse>('/api/v1/auth/login', req),

    refresh: (req: RefreshRequest) =>
      client.post<LoginResponse>('/api/v1/auth/refresh', req),

    logout: (req: LogoutRequest) =>
      client.post<void>('/api/v1/auth/logout', req),

    me: () =>
      client.get<MeResponse>('/api/v1/auth/me'),
  };
}
