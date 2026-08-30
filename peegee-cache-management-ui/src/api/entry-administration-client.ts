import {
  bulkDeletePreviewSchema,
  bulkDeleteResultSchema,
  entryDeleteSelectionSchema,
  entryMetadataSchema,
  entrySetBodySchema,
  entrySetResultSchema,
  confirmedEntryDeleteSchema,
  type BulkDeletePreview,
  type BulkDeleteResult,
  type ConfirmedEntryDelete,
  type EntryDeleteSelection,
  type EntrySetBody,
  type EntrySetResult,
} from './entry-administration-schemas';
import type { EntryMetadata } from './inspection-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface EntryAdministrationClientPort {
  setEntry(setupId: string, encodedNamespace: string, encodedKey: string, body: EntrySetBody, observedVersion?: string): Promise<EntrySetResult>;
  expireEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string, ttlMillis: number): Promise<EntryMetadata>;
  persistEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<EntryMetadata>;
  touchEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string, refreshTtlMillis: number | null): Promise<EntryMetadata>;
  deleteEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<void>;
  previewBulkDelete(setupId: string, encodedNamespace: string, selection: EntryDeleteSelection): Promise<BulkDeletePreview>;
  executeBulkDelete(setupId: string, encodedNamespace: string, confirmation: ConfirmedEntryDelete): Promise<BulkDeleteResult>;
}

export class EntryAdministrationClient implements EntryAdministrationClientPort {
  constructor(private readonly sessionClient: SessionClient) {}

  async setEntry(setupId: string, encodedNamespace: string, encodedKey: string, body: EntrySetBody, observedVersion?: string): Promise<EntrySetResult> {
    const validated = entrySetBodySchema.safeParse(body);
    if (!validated.success) throw invalidRequest('Entry set request is invalid');
    const precondition = preconditionFor(validated.data.setMode, observedVersion);
    const payload = await this.sessionClient.requestJson(this.entryPath(setupId, encodedNamespace, encodedKey), {
      body: validated.data, method: 'PUT', ...precondition,
    });
    return parse(entrySetResultSchema, payload);
  }

  async expireEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string, ttlMillis: number): Promise<EntryMetadata> {
    if (!Number.isSafeInteger(ttlMillis) || ttlMillis < 1) throw invalidRequest('Entry TTL must be a positive safe integer');
    return this.metadataMutation(setupId, encodedNamespace, encodedKey, 'ttl', version, { ttlMillis });
  }

  async persistEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<EntryMetadata> {
    return this.metadataMutation(setupId, encodedNamespace, encodedKey, 'persist', version);
  }

  async touchEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string, refreshTtlMillis: number | null): Promise<EntryMetadata> {
    if (refreshTtlMillis !== null && (!Number.isSafeInteger(refreshTtlMillis) || refreshTtlMillis < 1)) {
      throw invalidRequest('Refresh TTL must be null or a positive safe integer');
    }
    return this.metadataMutation(setupId, encodedNamespace, encodedKey, 'touch', version, { refreshTtlMillis });
  }

  async deleteEntry(setupId: string, encodedNamespace: string, encodedKey: string, version: string): Promise<void> {
    await this.sessionClient.requestJson(this.entryPath(setupId, encodedNamespace, encodedKey), {
      method: 'DELETE', ifMatch: etag(version),
    });
  }

  async previewBulkDelete(setupId: string, encodedNamespace: string, selection: EntryDeleteSelection): Promise<BulkDeletePreview> {
    const validated = entryDeleteSelectionSchema.safeParse(selection);
    if (!validated.success) throw invalidRequest('Bulk entry selection is invalid');
    const payload = await this.sessionClient.requestJson(`${this.entriesPath(setupId, encodedNamespace)}/bulk-delete/preview`, {
      method: 'POST', body: validated.data,
    });
    return parse(bulkDeletePreviewSchema, payload);
  }

  async executeBulkDelete(setupId: string, encodedNamespace: string, confirmation: ConfirmedEntryDelete): Promise<BulkDeleteResult> {
    const validated = confirmedEntryDeleteSchema.safeParse(confirmation);
    if (!validated.success) throw invalidRequest('Bulk entry confirmation is invalid');
    const payload = await this.sessionClient.requestJson(`${this.entriesPath(setupId, encodedNamespace)}/bulk-delete/execute`, {
      method: 'POST', body: validated.data,
    });
    return parse(bulkDeleteResultSchema, payload);
  }

  private async metadataMutation(setupId: string, encodedNamespace: string, encodedKey: string, action: string, version: string, body?: unknown): Promise<EntryMetadata> {
    const payload = await this.sessionClient.requestJson(`${this.entryPath(setupId, encodedNamespace, encodedKey)}/${action}`, {
      method: 'POST', ifMatch: etag(version), body,
    });
    return parse(entryMetadataSchema, payload);
  }

  private entriesPath(setupId: string, encodedNamespace: string): string {
    return `/api/v1/setups/${encodeURIComponent(setupId)}/namespaces/${encodeURIComponent(encodedNamespace)}/entries`;
  }

  private entryPath(setupId: string, encodedNamespace: string, encodedKey: string): string {
    return `${this.entriesPath(setupId, encodedNamespace)}/${encodeURIComponent(encodedKey)}`;
  }
}

function preconditionFor(mode: EntrySetBody['setMode'], observedVersion?: string): { ifMatch?: string; ifNoneMatch?: '*' } {
  switch (mode) {
    case 'UPSERT': return {};
    case 'ONLY_IF_ABSENT': return { ifNoneMatch: '*' };
    case 'ONLY_IF_PRESENT': return { ifMatch: '*' };
    case 'ONLY_IF_VERSION_MATCHES':
      if (observedVersion === undefined) throw invalidRequest('Observed version is required for compare-and-set');
      return { ifMatch: etag(observedVersion) };
  }
}

function etag(version: string): string {
  if (!/^(?:0|[1-9][0-9]*)$/u.test(version)) throw invalidRequest('Version must be an unsigned decimal string');
  return `"v${version}"`;
}

function parse<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, payload: unknown): T {
  const result = schema.safeParse(payload);
  if (!result.success) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned an incompatible entry administration response');
  return result.data;
}

function invalidRequest(message: string): ManagementClientError {
  return new ManagementClientError(400, 'VALIDATION_FAILED', message);
}
