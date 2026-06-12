/** General formatting helpers */

/** Format ISO-8601 timestamp to human-readable local time. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return '—';
  }
}

/** Format ISO-8601 timestamp to local date + time. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '—';
  }
}

/** Abbreviate UUIDs for display (first 8 chars). */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—';
  return id.substring(0, 8);
}

/** Map order state to a display-friendly label. */
export function orderStateLabel(state: string): string {
  const labels: Record<string, string> = {
    NEW: 'New',
    OPEN: 'Open',
    PARTIALLY_FILLED: 'Partial',
    FILLED: 'Filled',
    CANCEL_REQUESTED: 'Cancelling',
    CANCELLED: 'Cancelled',
    REJECTED: 'Rejected',
  };
  return labels[state] ?? state;
}
