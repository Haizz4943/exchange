/**
 * TokenStore — in-memory access token storage for embedded mode.
 *
 * In standalone mode, the token is managed by React state in AuthStandaloneProvider.
 * In embedded mode, the token is provided as a prop and stored in React state in AuthBridgeProvider.
 *
 * This module exists as a placeholder per §2.1 design spec.
 * A singleton token store is NOT used because we need per-panel-instance isolation.
 * See AuthStandaloneProvider.tsx and AuthBridge.tsx for the actual implementation.
 */
export {};
