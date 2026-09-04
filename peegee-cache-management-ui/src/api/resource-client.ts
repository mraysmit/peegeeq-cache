import type { BulkDeletePreview, BulkDeleteResult } from './entry-administration-schemas';
import {
  bulkDeletePreviewSchema, bulkDeleteResultSchema, confirmedCounterDeleteSchema,
  counterAdjustBodySchema, counterPageSchema, counterSchema, counterSelectionSchema, counterSetBodySchema,
  lockPageSchema, lockSchema, revealedLockOwnerSchema,
  type ConfirmedCounterDelete, type Counter, type CounterAdjustBody, type CounterPage,
  type CounterSelection, type CounterSetBody, type LockPage, type LockState, type RevealedLockOwner,
} from './resource-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface CounterQuery { readonly namespace?: string; readonly prefix?: string; readonly ttlState?: 'ALL_LIVE' | 'PERSISTENT' | 'EXPIRING' | 'INCLUDE_EXPIRED'; readonly cursor?: string; readonly limit?: number; readonly sort?: 'key:asc'; }
export interface LockQuery { readonly namespace?: string; readonly prefix?: string; readonly leaseState?: 'ACTIVE' | 'EXPIRING_SOON'; readonly cursor?: string; readonly limit?: number; }

export class ResourceClient {
  constructor(private readonly session: SessionClient) {}

  async counters(setupId: string, query: CounterQuery = {}): Promise<CounterPage> { return parse(counterPageSchema, await this.session.requestJson(`${this.setup(setupId)}/counters${queryString(query)}`)); }
  async counter(setupId: string, encodedNamespace: string, encodedKey: string): Promise<Counter> { return parse(counterSchema, await this.session.requestJson(this.counterPath(setupId, encodedNamespace, encodedKey))); }
  async setCounter(setupId: string, encodedNamespace: string, encodedKey: string, version: string | undefined, body: CounterSetBody): Promise<Counter> {
    const validated = request(counterSetBodySchema, body);
    return parse(counterSchema, await this.session.requestJson(this.counterPath(setupId, encodedNamespace, encodedKey), { method: 'PUT', body: validated, ...precondition(version) }));
  }
  async adjustCounter(setupId: string, encodedNamespace: string, encodedKey: string, version: string | undefined, body: CounterAdjustBody): Promise<Counter> {
    const validated = request(counterAdjustBodySchema, body);
    const condition = validated.createIfMissing ? { ifNoneMatch: '*' as const } : { ifMatch: etag(requiredVersion(version)) };
    return parse(counterSchema, await this.session.requestJson(`${this.counterPath(setupId, encodedNamespace, encodedKey)}/increment`, { method: 'POST', body: validated, ...condition }));
  }
  async expireCounter(setupId: string, encodedNamespace: string, encodedKey: string, version: string, ttlMillis: number): Promise<Counter> { return this.counterMutation(setupId, encodedNamespace, encodedKey, 'ttl', version, { ttlMillis: positive(ttlMillis) }); }
  async persistCounter(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<Counter> { return this.counterMutation(setupId, encodedNamespace, encodedKey, 'persist', version); }
  async deleteCounter(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<void> { await this.session.requestJson(this.counterPath(setupId, encodedNamespace, encodedKey), { method: 'DELETE', ifMatch: etag(version) }); }
  async previewCounterBulkDelete(setupId: string, selection: CounterSelection): Promise<BulkDeletePreview> { return parse(bulkDeletePreviewSchema, await this.session.requestJson(`${this.setup(setupId)}/counters/bulk-delete/preview`, { method: 'POST', body: request(counterSelectionSchema, selection) })); }
  async executeCounterBulkDelete(setupId: string, confirmation: ConfirmedCounterDelete): Promise<BulkDeleteResult> { return parse(bulkDeleteResultSchema, await this.session.requestJson(`${this.setup(setupId)}/counters/bulk-delete/execute`, { method: 'POST', body: request(confirmedCounterDeleteSchema, confirmation) })); }

  async locks(setupId: string, query: LockQuery = {}): Promise<LockPage> { return parse(lockPageSchema, await this.session.requestJson(`${this.setup(setupId)}/locks${queryString(query)}`)); }
  async lock(setupId: string, encodedNamespace: string, encodedKey: string): Promise<LockState> { return parse(lockSchema, await this.session.requestJson(this.lockPath(setupId, encodedNamespace, encodedKey))); }
  async revealLockOwner(setupId: string, encodedNamespace: string, encodedKey: string, reason?: string): Promise<RevealedLockOwner> {
    const body = reason === undefined || reason.trim() === '' ? {} : { reason: reason.trim() };
    return parse(revealedLockOwnerSchema, await this.session.requestSensitiveJson(`${this.lockPath(setupId, encodedNamespace, encodedKey)}/owner/reveal`, { method: 'POST', body }));
  }
  async forceReleaseLock(setupId: string, encodedNamespace: string, encodedKey: string, version: string, confirmationKey: string, reason?: string): Promise<void> {
    const normalized = reason?.trim();
    await this.session.requestJson(`${this.lockPath(setupId, encodedNamespace, encodedKey)}/force-release`, { method: 'POST', ifMatch: etag(version), body: normalized === undefined || normalized === '' ? { confirmationKey } : { confirmationKey, reason: normalized } });
  }

  private async counterMutation(setupId: string, encodedNamespace: string, encodedKey: string, action: string, version: string, body?: unknown): Promise<Counter> { return parse(counterSchema, await this.session.requestJson(`${this.counterPath(setupId, encodedNamespace, encodedKey)}/${action}`, { method: 'POST', ifMatch: etag(version), body })); }
  private counterPath(setupId: string, namespace: string, key: string): string { return `${this.setup(setupId)}/namespaces/${encodeURIComponent(namespace)}/counters/${encodeURIComponent(key)}`; }
  private lockPath(setupId: string, namespace: string, key: string): string { return `${this.setup(setupId)}/namespaces/${encodeURIComponent(namespace)}/locks/${encodeURIComponent(key)}`; }
  private setup(setupId: string): string { return `/api/v1/setups/${encodeURIComponent(setupId)}`; }
}

function parse<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, value: unknown): T { const result = schema.safeParse(value); if (!result.success) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned an incompatible counter or lock response'); return result.data; }
function request<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, value: unknown): T { const result = schema.safeParse(value); if (!result.success) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'Counter request is invalid'); return result.data; }
function requiredVersion(version?: string): string { if (version === undefined) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'Observed version is required'); return version; }
function precondition(version?: string): { ifMatch?: string; ifNoneMatch?: '*' } { return version === undefined ? { ifNoneMatch: '*' } : { ifMatch: etag(version) }; }
function etag(version: string): string { if (!/^(?:0|[1-9][0-9]*)$/u.test(version)) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'Version is invalid'); return `"v${version}"`; }
function positive(value: number): number { if (!Number.isSafeInteger(value) || value < 1) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'TTL must be positive'); return value; }
function queryString(query: CounterQuery | LockQuery): string { const values = Object.entries(query).filter(([, value]) => value !== undefined && value !== '').map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`); return values.length === 0 ? '' : `?${values.join('&')}`; }
