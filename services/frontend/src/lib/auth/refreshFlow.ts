/**
 * refreshFlow — token refresh coordination.
 *
 * Key concern from §15 note 11: "WS reconnection while refresh is in flight."
 * If two callers (API 401 + WS 4401) trigger refresh simultaneously,
 * both should await the same in-flight refresh promise (not make two separate calls).
 *
 * TODO: implement idempotent shared-promise refresh gate here.
 * Current implementation in AuthStandaloneProvider calls refresh directly without
 * this guard — safe for MVP since WS reconnect is handled separately.
 */

/**
 * Creates a refresh gate that ensures only one refresh is in-flight at a time.
 * All concurrent callers await the same promise.
 */
export function createRefreshGate(refreshFn: () => Promise<string>) {
  let inFlight: Promise<string> | null = null;

  return async function gatedRefresh(): Promise<string> {
    if (inFlight) return inFlight;
    inFlight = refreshFn().finally(() => {
      inFlight = null;
    });
    return inFlight;
  };
}
