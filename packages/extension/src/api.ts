import type {
  LoginRequest,
  LoginResponse,
  PullResponse,
  PushRecord,
  PushResponse,
  RegisterDeviceRequest,
  RegisterDeviceResponse,
  RegisterRequest,
  RegisterResponse,
  WaitResponse,
} from '@omnisync/protocol';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

export class ApiClient {
  constructor(
    readonly baseUrl: string,
    private token: string | null = null,
    private readonly fetchImpl: Fetch = (i, init) => fetch(i, init),
  ) {}

  setToken(token: string | null) {
    this.token = token;
  }

  private async call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    const res = await this.fetchImpl(this.baseUrl.replace(/\/+$/, '') + path, {
      method,
      headers: {
        ...(body !== undefined && { 'content-type': 'application/json' }),
        ...(this.token && { authorization: `Bearer ${this.token}` }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'omit',
      cache: 'no-store',
      signal,
    });
    if (!res.ok) {
      const err = (await res.json().catch(() => null)) as { error?: string } | null;
      throw new ApiError(res.status, err?.error ?? `HTTP ${res.status}`);
    }
    return (res.status === 204 ? undefined : await res.json()) as T;
  }

  register = (b: RegisterRequest) => this.call<RegisterResponse>('POST', '/v1/accounts', b);
  login = (b: LoginRequest) => this.call<LoginResponse>('POST', '/v1/sessions', b);
  logout = () => this.call<void>('DELETE', '/v1/sessions/current');
  registerDevice = (b: RegisterDeviceRequest) => this.call<RegisterDeviceResponse>('POST', '/v1/devices', b);
  push = (records: PushRecord[]) => this.call<PushResponse>('POST', '/v1/records', { records });
  /** Long-poll until something newer than `since` exists (or timeout). */
  waitForChanges = (since: number, timeoutSec: number) =>
    this.call<WaitResponse>('GET', `/v1/records/wait?since=${since}&timeout=${timeoutSec}`, undefined, AbortSignal.timeout((timeoutSec + 4) * 1000));
  pull = (since: number, limit = 500) => this.call<PullResponse>('GET', `/v1/records?since=${since}&limit=${limit}`);
}
