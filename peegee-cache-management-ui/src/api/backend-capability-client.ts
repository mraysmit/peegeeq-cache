import {
  acquireLockRequestSchema,
  acquireLockResultSchema,
  batchGetRequestSchema,
  batchGetResultSchema,
  batchSetRequestSchema,
  batchSetResultSchema,
  batchDeleteRequestSchema,
  batchDeleteResultSchema,
  cacheMetricsSnapshotSchema,
  entryExistsResultSchema,
  lockOwnerRequestSchema,
  lockOwnershipResultSchema,
  releaseLockResultSchema,
  renewLockRequestSchema,
  renewLockResultSchema,
  scanEntriesRequestSchema,
  scanEntriesResultSchema,
  type AcquireLockRequest,
  type AcquireLockResult,
  type BatchGetRequest,
  type BatchGetResult,
  type BatchSetRequest,
  type BatchSetResult,
  type BatchDeleteRequest,
  type BatchDeleteResult,
  type CacheMetricsSnapshot,
  type RenewLockRequest,
  type ScanEntriesRequest,
  type ScanEntriesResult,
} from './backend-capability-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface BackendCapabilityClientPort {
  entryExists(setupId: string, encodedNamespace: string, encodedKey: string): Promise<boolean>;
  batchGetEntries(setupId: string, request: BatchGetRequest): Promise<BatchGetResult>;
  batchSetEntries(setupId: string, request: BatchSetRequest): Promise<BatchSetResult>;
  batchDeleteEntries(setupId: string, request: BatchDeleteRequest): Promise<BatchDeleteResult>;
  scanEntries(setupId: string, request: ScanEntriesRequest): Promise<ScanEntriesResult>;
  acquireLock(setupId: string, encodedNamespace: string, encodedKey: string, request: AcquireLockRequest): Promise<AcquireLockResult>;
  renewLock(setupId: string, encodedNamespace: string, encodedKey: string, request: RenewLockRequest): Promise<boolean>;
  releaseLock(setupId: string, encodedNamespace: string, encodedKey: string, ownerToken: string): Promise<boolean>;
  isLockHeldBy(setupId: string, encodedNamespace: string, encodedKey: string, ownerToken: string): Promise<boolean>;
  cacheMetrics(setupId: string): Promise<CacheMetricsSnapshot>;
}

export class BackendCapabilityClient implements BackendCapabilityClientPort {
  constructor(private readonly session: SessionClient) {}

  async entryExists(setupId: string, encodedNamespace: string, encodedKey: string): Promise<boolean> {
    const result = parse(entryExistsResultSchema, await this.session.requestSensitiveJson(
      `${this.setup(setupId)}/namespaces/${encodeURIComponent(encodedNamespace)}/entries/${encodeURIComponent(encodedKey)}/exists`,
      {},
    ));
    return result.exists;
  }

  async batchGetEntries(setupId: string, request: BatchGetRequest): Promise<BatchGetResult> {
    return parse(batchGetResultSchema, await this.post(setupId, 'entries/batch-get', validate(batchGetRequestSchema, request)));
  }

  async batchSetEntries(setupId: string, request: BatchSetRequest): Promise<BatchSetResult> {
    return parse(batchSetResultSchema, await this.post(setupId, 'entries/batch-set', validate(batchSetRequestSchema, request)));
  }

  async batchDeleteEntries(setupId: string, request: BatchDeleteRequest): Promise<BatchDeleteResult> {
    return parse(batchDeleteResultSchema, await this.post(setupId, 'entries/batch-delete', validate(batchDeleteRequestSchema, request)));
  }

  async scanEntries(setupId: string, request: ScanEntriesRequest): Promise<ScanEntriesResult> {
    return parse(scanEntriesResultSchema, await this.post(setupId, 'entries/scan', validate(scanEntriesRequestSchema, request)));
  }

  async acquireLock(setupId: string, encodedNamespace: string, encodedKey: string, request: AcquireLockRequest): Promise<AcquireLockResult> {
    return parse(acquireLockResultSchema, await this.lockPost(
      setupId, encodedNamespace, encodedKey, 'acquire', validate(acquireLockRequestSchema, request),
    ));
  }

  async renewLock(setupId: string, encodedNamespace: string, encodedKey: string, request: RenewLockRequest): Promise<boolean> {
    const result = parse(renewLockResultSchema, await this.lockPost(
      setupId, encodedNamespace, encodedKey, 'renew', validate(renewLockRequestSchema, request),
    ));
    return result.renewed;
  }

  async releaseLock(setupId: string, encodedNamespace: string, encodedKey: string, ownerToken: string): Promise<boolean> {
    const result = parse(releaseLockResultSchema, await this.lockPost(
      setupId, encodedNamespace, encodedKey, 'release', validate(lockOwnerRequestSchema, { ownerToken }),
    ));
    return result.released;
  }

  async isLockHeldBy(setupId: string, encodedNamespace: string, encodedKey: string, ownerToken: string): Promise<boolean> {
    const result = parse(lockOwnershipResultSchema, await this.lockPost(
      setupId, encodedNamespace, encodedKey, 'ownership', validate(lockOwnerRequestSchema, { ownerToken }),
    ));
    return result.heldByOwner;
  }

  async cacheMetrics(setupId: string): Promise<CacheMetricsSnapshot> {
    return parse(cacheMetricsSnapshotSchema, await this.session.requestSensitiveJson(
      `${this.setup(setupId)}/cache-metrics`, {},
    ));
  }

  private post(setupId: string, path: string, body: unknown): Promise<unknown> {
    return this.session.requestSensitiveJson(
      `${this.setup(setupId)}/${path}`, { method: 'POST', body },
    );
  }

  private lockPost(
    setupId: string,
    encodedNamespace: string,
    encodedKey: string,
    action: string,
    body: unknown,
  ): Promise<unknown> {
    return this.session.requestSensitiveJson(
      `${this.setup(setupId)}/namespaces/${encodeURIComponent(encodedNamespace)}/locks/${encodeURIComponent(encodedKey)}/${action}`,
      { method: 'POST', body },
    );
  }

  private setup(setupId: string): string {
    return `/api/v1/setups/${encodeURIComponent(setupId)}`;
  }
}

function validate<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new ManagementClientError(400, 'VALIDATION_FAILED', 'Backend capability request is invalid');
  }
  return result.data;
}

function parse<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned an incompatible backend capability response');
  }
  return result.data;
}
