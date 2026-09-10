import {
  acquireLockResultSchema,
  batchDeleteResultSchema,
  batchGetResultSchema,
  batchSetResultSchema,
  cacheMetricsSnapshotSchema,
  entryExistsResultSchema,
  lockOwnershipResultSchema,
  releaseLockResultSchema,
  renewLockResultSchema,
  scanEntriesResultSchema,
} from '@src/api/backend-capability-schemas';
import { bulkDeletePreviewSchema, bulkDeleteResultSchema } from '@src/api/entry-administration-schemas';
import { decodeKey, decodeNamespace, encodeKey, encodeNamespace } from '@src/api/identifier-codec';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { counterPageSchema, counterSchema, lockPageSchema, lockSchema, revealedLockOwnerSchema, type Counter, type LockState } from '@src/api/resource-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';

import { startLoopbackServer, type LoopbackRequest, type LoopbackServer } from './loopback-server';

/**
 * Shared loopback fixture for the counter, lock, and advanced-operations pages. Counters and
 * locks are held in mutable `state` so committed mutations are visible to the next read, exactly
 * as PostgreSQL truth would be; every body is produced by the production Zod schema.
 */
export const resourceSession = currentSessionSchema.parse({
  user: 'resource-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'resource-pages-csrf-token-with-forty-six-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const NO_STORE = { 'cache-control': 'no-store', pragma: 'no-cache' } as const;
const SIGNED_MIN = -9_223_372_036_854_775_808n;
const SIGNED_MAX = 9_223_372_036_854_775_807n;

export const totalCounter = counterSchema.parse({
  namespace: 'orders', encodedNamespace: encodeNamespace('orders'), key: 'total', encodedKey: encodeKey('total'),
  value: '9223372036854775807', version: '3', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z',
  ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null },
});

export const processorLock = lockSchema.parse({
  namespace: 'orders', encodedNamespace: encodeNamespace('orders'), key: 'processor', encodedKey: encodeKey('processor'),
  fencingToken: '9223372036854775807', version: '4', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z',
  leaseExpiresAt: '2099-08-29T10:02:00Z', leaseRemainingMillis: 60_000, owner: { state: 'MASKED' },
});

export interface ResourceFixtureState {
  counters: Counter[];
  locks: LockState[];
  /** Owner token returned by lock reveal — never appears in any metadata body. */
  ownerToken: string;
  autoHideAfterMillis: number;
}

export interface ResourceFixture {
  readonly server: LoopbackServer;
  readonly store: ManagementStore;
  readonly sessionClient: SessionClient;
  readonly state: ResourceFixtureState;
  requests(predicate: (request: LoopbackRequest) => boolean): LoopbackRequest[];
  close(): Promise<void>;
}

export async function startResourceFixture(): Promise<ResourceFixture> {
  const state: ResourceFixtureState = { counters: [totalCounter], locks: [processorLock], ownerToken: 'sensitive-owner', autoHideAfterMillis: 60_000 };
  const now = '2026-08-29T10:02:00Z';
  const body = (request: LoopbackRequest) => (typeof request.body === 'object' && request.body !== null ? request.body as Record<string, unknown> : {});
  const versionMatches = (request: LoopbackRequest, version: string) => request.headers['if-match'] === `"v${version}"`;

  const server = await startLoopbackServer((request, respond) => {
    const { method, path } = request;
    const setup = '/api/v1/setups/primary-cache';
    if (method === 'GET' && path === '/api/v1/session') return respond.json(200, resourceSession);
    if (method === 'GET' && path === '/api/v1/setups') return respond.json(200, setupSummaryListSchema.parse({ items: [] }));

    if (method === 'GET' && path === `${setup}/counters`) {
      const namespace = request.query.get('namespace');
      const prefix = request.query.get('prefix');
      const items = state.counters
        .filter((item) => (namespace === null || item.namespace === namespace) && (prefix === null || item.key.startsWith(prefix)))
        .sort((left, right) => left.namespace.localeCompare(right.namespace) || left.key.localeCompare(right.key));
      return respond.json(200, counterPageSchema.parse({ items, nextCursor: null, hasMore: false }));
    }
    const counterMatch = /^\/api\/v1\/setups\/primary-cache\/namespaces\/([^/]+)\/counters\/([^/]+)(?:\/(increment|ttl|persist))?$/u.exec(path);
    if (counterMatch !== null) {
      const [, encodedNamespace, encodedKey, action] = counterMatch;
      const existing = state.counters.find((item) => item.encodedNamespace === encodedNamespace && item.encodedKey === encodedKey);
      if (method === 'GET' && action === undefined) {
        return existing === undefined ? respond.problem(404, 'COUNTER_NOT_FOUND', 'No such counter') : respond.json(200, counterSchema.parse(existing));
      }
      if (method === 'PUT' && action === undefined) {
        const { value, ttlMillis } = body(request) as { value: string; ttlMillis: number | null };
        if (existing !== undefined && !versionMatches(request, existing.version)) return respond.problem(412, 'VERSION_MISMATCH', 'The counter changed');
        if (existing === undefined && request.headers['if-none-match'] !== '*') return respond.problem(412, 'VERSION_MISMATCH', 'Absence precondition required');
        const updated = counterSchema.parse({
          ...(existing ?? { namespace: decodeNamespace(encodedNamespace!), encodedNamespace, key: decodeKey(encodedKey!), encodedKey, createdAt: now, ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } }),
          value, version: existing === undefined ? '1' : (BigInt(existing.version) + 1n).toString(), updatedAt: now,
          ...(ttlMillis === null ? {} : { ttl: { state: 'EXPIRING', ttlMillis, expiresAt: '2026-08-29T10:03:00Z' } }),
        });
        state.counters = [...state.counters.filter((item) => item !== existing), updated];
        return respond.json(200, updated);
      }
      if (method === 'POST' && action === 'increment') {
        const { delta, createIfMissing, ttlMillis } = body(request) as { delta: string; createIfMissing: boolean; ttlMillis: number | null };
        if (existing === undefined && !createIfMissing) return respond.problem(404, 'COUNTER_NOT_FOUND', 'No such counter');
        if (existing !== undefined && !versionMatches(request, existing.version)) return respond.problem(412, 'VERSION_MISMATCH', 'The counter changed');
        const next = BigInt(existing?.value ?? '0') + BigInt(delta);
        if (next < SIGNED_MIN || next > SIGNED_MAX) return respond.problem(409, 'COUNTER_OVERFLOW', 'The adjustment leaves the signed 64-bit range');
        const updated = counterSchema.parse({
          ...(existing ?? { namespace: decodeNamespace(encodedNamespace!), encodedNamespace, key: decodeKey(encodedKey!), encodedKey, createdAt: now, ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } }),
          value: next.toString(), version: existing === undefined ? '1' : (BigInt(existing.version) + 1n).toString(), updatedAt: now,
          ...(ttlMillis === null ? {} : { ttl: { state: 'EXPIRING', ttlMillis, expiresAt: '2026-08-29T10:03:00Z' } }),
        });
        state.counters = [...state.counters.filter((item) => item !== existing), updated];
        return respond.json(200, updated);
      }
      if (existing === undefined) return respond.problem(404, 'COUNTER_NOT_FOUND', 'No such counter');
      if (!versionMatches(request, existing.version)) return respond.problem(412, 'VERSION_MISMATCH', 'The counter changed');
      if (method === 'POST' && action === 'ttl') {
        const { ttlMillis } = body(request) as { ttlMillis: number };
        const updated = counterSchema.parse({ ...existing, version: (BigInt(existing.version) + 1n).toString(), updatedAt: now, ttl: { state: 'EXPIRING', ttlMillis, expiresAt: '2026-08-29T10:03:00Z' } });
        state.counters = state.counters.map((item) => (item === existing ? updated : item));
        return respond.json(200, updated);
      }
      if (method === 'POST' && action === 'persist') {
        const updated = counterSchema.parse({ ...existing, version: (BigInt(existing.version) + 1n).toString(), updatedAt: now, ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } });
        state.counters = state.counters.map((item) => (item === existing ? updated : item));
        return respond.json(200, updated);
      }
      if (method === 'DELETE' && action === undefined) {
        state.counters = state.counters.filter((item) => item !== existing);
        return respond.noContent();
      }
    }
    if (method === 'POST' && path === `${setup}/counters/bulk-delete/preview`) {
      const { targets } = body(request) as { targets: Array<{ namespace: string; key: string; version: string }> };
      return respond.json(200, bulkDeletePreviewSchema.parse({
        previewToken: 'c'.repeat(32), expiresAt: '2099-08-29T10:05:00Z', setupId: 'primary-cache', namespace: targets[0]?.namespace ?? 'orders',
        resolvedCount: String(targets.length), totalBytes: '8', sampleKeys: targets.map((target) => target.key), confirmationPhrase: `DELETE ${targets.length} COUNTERS`,
      }));
    }
    if (method === 'POST' && path === `${setup}/counters/bulk-delete/execute`) {
      const deleted = state.counters.length;
      state.counters = [];
      return respond.json(200, bulkDeleteResultSchema.parse({ processedCount: String(deleted), deletedCount: String(deleted), conflictCount: '0', missingCount: '0', failedCount: '0', conflicts: [] }));
    }

    if (method === 'GET' && path === `${setup}/locks`) return respond.json(200, lockPageSchema.parse({ items: state.locks, nextCursor: null, hasMore: false }));
    const lockMatch = /^\/api\/v1\/setups\/primary-cache\/namespaces\/([^/]+)\/locks\/([^/]+)(?:\/(owner\/reveal|force-release|acquire|renew|release|ownership))?$/u.exec(path);
    if (lockMatch !== null) {
      const [, encodedNamespace, encodedKey, action] = lockMatch;
      const existing = state.locks.find((item) => item.encodedNamespace === encodedNamespace && item.encodedKey === encodedKey);
      if (method === 'GET' && action === undefined) return existing === undefined ? respond.problem(404, 'LOCK_NOT_FOUND', 'No such lock') : respond.json(200, lockSchema.parse(existing));
      if (method === 'POST' && action === 'owner/reveal' && existing !== undefined) {
        return respond.json(200, revealedLockOwnerSchema.parse({ key: existing.key, ownerToken: state.ownerToken, version: existing.version, revealedAt: '2026-08-29T10:01:30Z', autoHideAfterMillis: state.autoHideAfterMillis }), NO_STORE);
      }
      if (method === 'POST' && action === 'force-release' && existing !== undefined) {
        if (!versionMatches(request, existing.version)) return respond.problem(412, 'VERSION_MISMATCH', 'The lock changed');
        state.locks = state.locks.filter((item) => item !== existing);
        return respond.noContent();
      }
      if (method === 'POST' && action === 'acquire') {
        const { ownerToken, issueFencingToken } = body(request) as { ownerToken: string; issueFencingToken: boolean };
        return respond.json(200, acquireLockResultSchema.parse({ acquired: true, namespace: 'orders', key: 'processor', ownerToken, fencingToken: issueFencingToken ? '7' : null, leaseExpiresAt: '2099-01-01T00:00:00Z' }), NO_STORE);
      }
      if (method === 'POST' && action === 'renew') return respond.json(200, renewLockResultSchema.parse({ renewed: true }), NO_STORE);
      if (method === 'POST' && action === 'release') return respond.json(200, releaseLockResultSchema.parse({ released: true }), NO_STORE);
      if (method === 'POST' && action === 'ownership') return respond.json(200, lockOwnershipResultSchema.parse({ heldByOwner: true }), NO_STORE);
    }

    if (method === 'GET' && /\/entries\/[^/]+\/exists$/u.test(path)) return respond.json(200, entryExistsResultSchema.parse({ exists: true }), NO_STORE);
    if (method === 'POST' && path === `${setup}/entries/batch-get`) {
      const { keys } = body(request) as { keys: Array<{ namespace: string; key: string }> };
      return respond.json(200, batchGetResultSchema.parse({ items: keys.map((item) => ({ ...item, found: false, entry: null })) }), NO_STORE);
    }
    if (method === 'POST' && path === `${setup}/entries/batch-set`) {
      const { entries } = body(request) as { entries: Array<{ namespace: string; key: string }> };
      return respond.json(200, batchSetResultSchema.parse({ items: entries.map((entry) => ({ namespace: entry.namespace, key: entry.key, applied: true, newVersion: '2', previousEntry: null })) }), NO_STORE);
    }
    if (method === 'POST' && path === `${setup}/entries/batch-delete`) {
      const { keys } = body(request) as { keys: unknown[] };
      return respond.json(200, batchDeleteResultSchema.parse({ deletedCount: String(keys.length) }), NO_STORE);
    }
    if (method === 'POST' && path === `${setup}/entries/scan`) return respond.json(200, scanEntriesResultSchema.parse({ entries: [], nextCursor: null, hasMore: false }), NO_STORE);
    if (method === 'GET' && path === `${setup}/cache-metrics`) {
      return respond.json(200, cacheMetricsSnapshotSchema.parse({
        cacheGets: '1', cacheHits: '1', cacheMisses: '0', cacheSets: '1', cacheSetsApplied: '1', cacheDeletes: '0', counterIncrements: '0', counterSets: '0', counterDeletes: '0',
        lockAcquires: '1', lockAcquiresGranted: '1', lockRenewals: '0', lockReleases: '0', publishes: '0', subscribes: '0',
      }), NO_STORE);
    }
    return respond.problem(404, 'NOT_FOUND', `no fixture for ${method} ${path}`);
  });
  const sessionClient = new SessionClient(server.baseUrl);
  await sessionClient.load();
  const store = createManagementStore(createManagementClients(sessionClient));
  return {
    server, store, sessionClient, state,
    requests: (predicate) => server.requests.filter(predicate),
    close: async () => { sessionClient.clear(); await server.close(); },
  };
}
