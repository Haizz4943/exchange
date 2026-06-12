/**
 * WebSocket client — single connection per panel instance, multiplexing all subscriptions.
 * Design: §6.2 of SystemDesign_Appendix_Frontend.md
 */

type Channel = string;
type MessageHandler = (payload: unknown) => void;
type StatusListener = (status: WsStatus) => void;

export type WsStatus = 'idle' | 'connecting' | 'open' | 'closing' | 'error';

class ExponentialBackoff {
  private attempt = 0;
  constructor(
    private minMs: number,
    private maxMs: number,
  ) {}

  next(): number {
    const delay = Math.min(this.minMs * Math.pow(2, this.attempt), this.maxMs);
    this.attempt++;
    // Add 10% jitter
    return delay * (0.9 + Math.random() * 0.2);
  }

  reset() {
    this.attempt = 0;
  }
}

export class WsClient {
  private socket: WebSocket | null = null;
  /** Ref count per channel — how many subscribers have called subscribe() */
  private channelRefs = new Map<Channel, number>();
  /** Schema-based message handlers: schemaName → Set<handler> */
  private schemaHandlers = new Map<string, Set<MessageHandler>>();
  /** Queue of messages to send when connection opens */
  private sendQueue: string[] = [];
  private backoff = new ExponentialBackoff(1_000, 30_000);
  private _status: WsStatus = 'idle';
  private statusListeners = new Set<StatusListener>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private baseUrl: string,
    private getToken: () => string | null,
  ) {}

  // ── Public API ─────────────────────────────────────────────────────────────

  get status(): WsStatus {
    return this._status;
  }

  connect() {
    if (this._status !== 'idle') return;
    this.setStatus('connecting');
    const token = this.getToken();
    const wsUrl = `${this.baseUrl}/ws${token ? `?token=${encodeURIComponent(token)}` : ''}`;
    try {
      this.socket = new WebSocket(wsUrl);
      this.socket.onopen = () => this.handleOpen();
      this.socket.onmessage = (ev) => this.handleMessage(ev);
      this.socket.onclose = (ev) => this.handleClose(ev);
      this.socket.onerror = () => this.setStatus('error');
    } catch (err) {
      console.error('[WsClient] Failed to construct WebSocket:', err);
      this.setStatus('error');
    }
  }

  /**
   * Subscribe to a channel. Returns an unsubscribe function.
   * Ref-counted: first subscriber sends SUBSCRIBE, last unsub sends UNSUBSCRIBE.
   */
  subscribe(channel: Channel): () => void {
    const prev = this.channelRefs.get(channel) ?? 0;
    this.channelRefs.set(channel, prev + 1);
    if (prev === 0 && this._status === 'open') {
      this.send({ op: 'subscribe', channels: [channel] });
    }
    return () => {
      const curr = this.channelRefs.get(channel) ?? 0;
      if (curr <= 1) {
        this.channelRefs.delete(channel);
        if (this._status === 'open') this.send({ op: 'unsubscribe', channels: [channel] });
      } else {
        this.channelRefs.set(channel, curr - 1);
      }
    };
  }

  /**
   * Register a handler for messages with a specific `schema` field.
   * Returns an unregister function.
   */
  onSchema(schema: string, handler: MessageHandler): () => void {
    if (!this.schemaHandlers.has(schema)) {
      this.schemaHandlers.set(schema, new Set());
    }
    this.schemaHandlers.get(schema)!.add(handler);
    return () => {
      this.schemaHandlers.get(schema)?.delete(handler);
    };
  }

  /** Alias kept for WsStoreSyncer compat with §5.5 naming */
  onMessage(schema: string, handler: MessageHandler): () => void {
    return this.onSchema(schema, handler);
  }

  onStatusChange(listener: StatusListener): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  close() {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.setStatus('closing');
    this.socket?.close();
  }

  // ── Private ────────────────────────────────────────────────────────────────

  private setStatus(s: WsStatus) {
    this._status = s;
    this.statusListeners.forEach((l) => l(s));
  }

  private handleOpen() {
    this.setStatus('open');
    this.backoff.reset();
    // Re-subscribe to all currently tracked channels
    const channels = Array.from(this.channelRefs.keys());
    if (channels.length > 0) this.send({ op: 'subscribe', channels });
    // Flush queued messages
    this.sendQueue.forEach((msg) => this.socket!.send(msg));
    this.sendQueue = [];
  }

  private handleMessage(ev: MessageEvent<string>) {
    let msg: { schema?: string; payload?: unknown };
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (!msg.schema) return;
    const handlers = this.schemaHandlers.get(msg.schema);
    if (handlers) {
      handlers.forEach((h) => {
        try {
          h(msg.payload);
        } catch (err) {
          console.error(`[WsClient] Handler error for schema ${msg.schema}:`, err);
        }
      });
    }
  }

  private handleClose(ev: CloseEvent) {
    this.socket = null;
    // Code 4401 = token expired — do NOT auto-reconnect; let auth layer handle
    if (ev.code === 4401) {
      this.setStatus('idle');
      return;
    }
    this.setStatus('idle');
    const delay = this.backoff.next();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  private send(msg: unknown) {
    const s = JSON.stringify(msg);
    if (this._status === 'open' && this.socket) {
      this.socket.send(s);
    } else {
      this.sendQueue.push(s);
    }
  }
}
