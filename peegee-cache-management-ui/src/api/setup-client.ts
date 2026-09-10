import type {
  SetupConnectionRequestContract,
  SetupRegistrationRequestContract,
} from './openapi-contract';
import { ManagementClientError, SessionClient } from './session-client';
import {
  setupConnectionTestSchema,
  setupDetailsSchema,
  setupHealthSchema,
  setupSummaryListSchema,
  setupSummarySchema,
  type SetupConnectionTest,
  type SetupDetails,
  type SetupHealth,
  type SetupSummary,
} from './setup-schemas';

export type SetupConnectionRequest = SetupConnectionRequestContract;
export type SetupRegistrationRequest = SetupRegistrationRequestContract;

export class SetupClient {
  constructor(private readonly sessionClient: SessionClient) {}

  async list(): Promise<SetupSummary[]> {
    return this.parse(
      setupSummaryListSchema,
      await this.sessionClient.requestJson('/api/v1/setups'),
    ).items;
  }

  async details(setupId: string): Promise<SetupDetails> {
    return this.parse(
      setupDetailsSchema,
      await this.sessionClient.requestJson(this.setupPath(setupId)),
    );
  }

  async health(setupId: string): Promise<SetupHealth> {
    return this.parse(
      setupHealthSchema,
      await this.sessionClient.requestJson(`${this.setupPath(setupId)}/health`),
    );
  }

  async testConnection(request: SetupConnectionRequest): Promise<SetupConnectionTest> {
    return this.parse(
      setupConnectionTestSchema,
      await this.sessionClient.requestJson('/api/v1/setups/actions/test', {
        body: request,
        method: 'POST',
      }),
    );
  }

  async register(request: SetupRegistrationRequest): Promise<SetupSummary> {
    return this.parse(
      setupSummarySchema,
      await this.sessionClient.requestJson('/api/v1/setups', {
        body: request,
        method: 'POST',
      }),
    );
  }

  async testRegistered(setupId: string): Promise<SetupConnectionTest> {
    return this.parse(
      setupConnectionTestSchema,
      await this.sessionClient.requestJson(`${this.setupPath(setupId)}/test`, { method: 'POST' }),
    );
  }

  async connect(setupId: string): Promise<SetupSummary> {
    return this.parse(
      setupSummarySchema,
      await this.sessionClient.requestJson(`${this.setupPath(setupId)}/connect`, { method: 'POST' }),
    );
  }

  async detach(setupId: string): Promise<void> {
    await this.sessionClient.requestJson(`${this.setupPath(setupId)}/detach`, { method: 'POST' });
  }

  async forget(setupId: string): Promise<void> {
    await this.sessionClient.requestJson(this.setupPath(setupId), { method: 'DELETE' });
  }

  private setupPath(setupId: string): string {
    return `/api/v1/setups/${encodeURIComponent(setupId)}`;
  }

  private parse<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, payload: unknown): T {
    const parsed = schema.safeParse(payload);
    if (!parsed.success) {
      throw new ManagementClientError(
        502,
        'RESPONSE_CONTRACT_INVALID',
        'The server returned an incompatible setup response',
      );
    }
    return parsed.data;
  }
}
