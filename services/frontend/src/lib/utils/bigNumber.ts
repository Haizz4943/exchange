/**
 * Decimal formatting utilities.
 * All monetary values arrive as strings from the API to preserve precision.
 * Never use native JS floats for financial calculations.
 */

/** Format a string decimal to a fixed number of decimal places for display. */
export function formatDecimal(value: string | number | null | undefined, decimals = 8): string {
  if (value == null || value === '') return '—';
  const num = typeof value === 'string' ? parseFloat(value) : value;
  if (isNaN(num)) return '—';
  return num.toFixed(decimals);
}

/** Format a price string with commas and appropriate decimals. */
export function formatPrice(value: string | number | null | undefined, decimals = 2): string {
  if (value == null || value === '') return '—';
  const num = typeof value === 'string' ? parseFloat(value) : value;
  if (isNaN(num)) return '—';
  return num.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/** Format a quantity (base asset) — typically 8 decimal places. */
export function formatQuantity(value: string | number | null | undefined): string {
  return formatDecimal(value, 8);
}

/** Parse a string to float, returning 0 on failure. Safe for form inputs. */
export function parseDecimalSafe(value: string | undefined | null): number {
  if (!value) return 0;
  const n = parseFloat(value);
  return isNaN(n) ? 0 : n;
}
