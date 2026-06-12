/**
 * Normalized API error — thrown by ApiClient on non-2xx responses.
 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly correlationId: string,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** True if the error is a known business-logic code (not a server crash). */
  get isBusinessError(): boolean {
    return this.status >= 400 && this.status < 500 && this.code !== 'UNKNOWN';
  }

  /** User-facing message — either the API message or a generic fallback. */
  get userMessage(): string {
    if (this.code === 'TOKEN_EXPIRED') return 'Your session has expired. Please log in again.';
    if (this.code === 'INSUFFICIENT_AVAILABLE_BALANCE') {
      const avail = (this.details?.available as string) ?? '';
      const asset = (this.details?.asset as string) ?? '';
      return avail
        ? `Insufficient balance. Available: ${avail}${asset ? ' ' + asset : ''}.`
        : 'Insufficient available balance.';
    }
    if (this.status === 429) return 'Too many requests. Please wait a moment and try again.';
    if (this.status >= 500)
      return `Something went wrong. Please try again. [ref: ${this.correlationId.substring(0, 8)}]`;
    return this.message;
  }
}
