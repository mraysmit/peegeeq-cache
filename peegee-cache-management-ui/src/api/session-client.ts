import { currentSessionSchema, managementProblemSchema } from './protocol-schemas';
import type { CurrentSession, ManagementProblem } from './protocol-schemas';
import { clearSetupScope } from '../state/scope-store';

export type BrowserSession = Omit<CurrentSession, 'csrfToken'>;

export interface ManagementRequest {
  readonly method?: 'GET' | 'POST' | 'DELETE';
  readonly body?: unknown;
}

let csrfToken: string | undefined;

export class ManagementClientError extends Error {
  override readonly name = 'ManagementClientError';

  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId?: string,
  ) {
    super(message);
  }
}

export class SessionClient {
  private readonly baseUrl: string;

  constructor(baseUrl = '') {
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  }

  async load(): Promise<BrowserSession> {
    const response = await fetch(this.url('/api/v1/session'), {
      credentials: 'include',
      headers: { accept: 'application/json' },
      method: 'GET',
    });
    return this.readSession(response);
  }

  async exchangeLocalToken(token: string): Promise<BrowserSession> {
    const response = await fetch(this.url('/api/v1/session/local'), {
      body: JSON.stringify({ token }),
      credentials: 'include',
      headers: {
        accept: 'application/json',
        'content-type': 'application/json',
      },
      method: 'POST',
    });
    return this.readSession(response);
  }

  async logoutLocal(): Promise<void> {
    const token = csrfToken;
    if (token === undefined) {
      throw new ManagementClientError(401, 'SESSION_STATE_MISSING', 'Session state is unavailable');
    }
    const response = await fetch(this.url('/api/v1/session/local'), {
      credentials: 'include',
      headers: {
        accept: 'application/json',
        'X-PeeGeeQ-CSRF': token,
      },
      method: 'DELETE',
    });
    if (response.status !== 204) {
      throw await this.readProblem(response);
    }
    csrfToken = undefined;
  }

  async requestJson(path: string, request: ManagementRequest = {}): Promise<unknown> {
    const method = request.method ?? 'GET';
    const headers: Record<string, string> = { accept: 'application/json' };
    if (method !== 'GET') {
      const token = csrfToken;
      if (token === undefined) {
        throw new ManagementClientError(
          401,
          'SESSION_STATE_MISSING',
          'Session state is unavailable',
        );
      }
      headers['X-PeeGeeQ-CSRF'] = token;
    }
    if (request.body !== undefined) {
      headers['content-type'] = 'application/json';
    }
    const response = await fetch(this.url(path), {
      body: request.body === undefined ? undefined : JSON.stringify(request.body),
      credentials: 'include',
      headers,
      method,
    });
    if (!response.ok) {
      if (response.status === 401) csrfToken = undefined;
      throw await this.readProblem(response);
    }
    if (response.status === 204) return undefined;
    return this.readJson(response);
  }

  clear(): void {
    csrfToken = undefined;
    clearSetupScope();
  }

  private url(path: string): string {
    return `${this.baseUrl}${path}`;
  }

  private async readSession(response: Response): Promise<BrowserSession> {
    if (!response.ok) {
      csrfToken = undefined;
      throw await this.readProblem(response);
    }
    const payload = await this.readJson(response);
    const parsed = currentSessionSchema.safeParse(payload);
    if (!parsed.success) {
      csrfToken = undefined;
      throw new ManagementClientError(
        502,
        'RESPONSE_CONTRACT_INVALID',
        'The server returned an incompatible session response',
      );
    }
    const { csrfToken: acceptedCsrfToken, ...session } = parsed.data;
    csrfToken = acceptedCsrfToken;
    return session;
  }

  private async readProblem(response: Response): Promise<ManagementClientError> {
    const payload = await this.readJson(response);
    const parsed = managementProblemSchema.safeParse(payload);
    if (!parsed.success) {
      return new ManagementClientError(
        response.status,
        'HTTP_REQUEST_FAILED',
        `The management request failed with status ${response.status}`,
      );
    }
    return problemError(parsed.data);
  }

  private async readJson(response: Response): Promise<unknown> {
    try {
      return await response.json() as unknown;
    } catch {
      throw new ManagementClientError(
        response.status,
        'RESPONSE_BODY_INVALID',
        'The server returned an unreadable response',
      );
    }
  }
}

function problemError(problem: ManagementProblem): ManagementClientError {
  return new ManagementClientError(
    problem.status,
    problem.code,
    problem.detail || problem.title,
    problem.correlationId,
  );
}
