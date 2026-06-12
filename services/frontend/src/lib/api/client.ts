import { generateCorrelationId } from '@/lib/utils/correlationId';
import { ApiError } from './errors';

export interface ApiClientOptions {
  baseUrl: string;
  getAccessToken: () => string | null;
  onUnauthorized: () => void | Promise<void>;
}

function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) p.append(k, String(v));
  }
  return p.toString();
}

export class ApiClient {
  constructor(private opts: ApiClientOptions) {}

  async request<T>(method: string, path: string, init: RequestInit = {}): Promise<T> {
    const correlationId = generateCorrelationId();
    const token = this.opts.getAccessToken();

    const headers = new Headers(init.headers);
    if (!(init.body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
    }
    headers.set('X-Correlation-Id', correlationId);
    if (token) headers.set('Authorization', `Bearer ${token}`);

    const res = await fetch(`${this.opts.baseUrl}${path}`, { ...init, method, headers });

    if (res.status === 401) {
      this.opts.onUnauthorized();
      throw new ApiError('TOKEN_EXPIRED', 'Session expired', 401, correlationId);
    }

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(
        body.code ?? body.error?.code ?? 'UNKNOWN',
        body.message ?? body.error?.message ?? res.statusText,
        res.status,
        correlationId,
        body.details ?? body.error?.details,
      );
    }

    if (res.status === 204) return undefined as T;
    return res.json() as Promise<T>;
  }

  get<T>(path: string, params?: Record<string, string | number | boolean | undefined | null>) {
    const q = params ? qs(params) : '';
    return this.request<T>('GET', q ? `${path}?${q}` : path);
  }

  post<T>(path: string, body?: unknown) {
    return this.request<T>('POST', path, { body: body !== undefined ? JSON.stringify(body) : undefined });
  }

  delete<T>(path: string) {
    return this.request<T>('DELETE', path);
  }
}

export { qs };
