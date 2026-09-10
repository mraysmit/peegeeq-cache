import { bulkDeletePreviewSchema, bulkDeleteResultSchema, entrySetResultSchema } from '@src/api/entry-administration-schemas';
import { encodeKey, encodeNamespace } from '@src/api/identifier-codec';
import { entryMetadataSchema, entryPageSchema, revealedEntryValueSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';

import { startLoopbackServer, type LoopbackRequest, type LoopbackServer } from './loopback-server';

/**
 * Shared loopback fixture for the entry pages: one namespace, one entry, deterministic
 * mutation responses. Every body is produced by the production Zod schema. Behaviour can be
 * adjusted per test through the returned `state`.
 */
export const entrySession = currentSessionSchema.parse({
  user: 'entry-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'entry-pages-csrf-token-with-forty-three-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

export const entryMetadata = entryMetadataSchema.parse({
  namespace: '客户/订单', encodedNamespace: encodeNamespace('客户/订单'),
  key: 'café/東京/🔒?x=1', encodedKey: encodeKey('café/東京/🔒?x=1'),
  valueType: 'STRING', sizeBytes: '17', version: '9007199254740993',
  createdAt: '2026-08-26T10:00:00Z', updatedAt: '2026-08-26T10:15:00Z', lastAccessedAt: null,
  ttl: { state: 'EXPIRING', ttlMillis: 45_000, expiresAt: '2026-08-26T10:15:45Z' },
});

export const ordersMetadata = entryMetadataSchema.parse({
  namespace: 'orders', encodedNamespace: encodeNamespace('orders'), key: 'order:1', encodedKey: encodeKey('order:1'),
  valueType: 'STRING', sizeBytes: '5', version: '3',
  createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', lastAccessedAt: null,
  ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null },
});

export interface EntryFixtureState {
  /** Current metadata served for the fixture entry (mutations replace it). */
  metadata: typeof entryMetadata;
  /** When set, PUT (set entry) responds with this problem instead of succeeding. */
  setEntryProblem?: { status: number; code: string; detail: string };
  /** Auto-hide window returned by reveal. */
  autoHideAfterMillis: number;
}

export interface EntryFixture {
  readonly server: LoopbackServer;
  readonly store: ManagementStore;
  readonly sessionClient: SessionClient;
  readonly state: EntryFixtureState;
  requests(predicate: (request: LoopbackRequest) => boolean): LoopbackRequest[];
  close(): Promise<void>;
}

export async function startEntryFixture(initial: typeof entryMetadata = entryMetadata): Promise<EntryFixture> {
  const state: EntryFixtureState = { metadata: initial, autoHideAfterMillis: 1_000 };
  const server = await startLoopbackServer((request, respond) => {
    const { method, path } = request;
    if (method === 'GET' && path === '/api/v1/session') return respond.json(200, entrySession);
    if (method === 'GET' && path === '/api/v1/setups') return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
    const entriesPath = `/api/v1/setups/primary-cache/namespaces/${state.metadata.encodedNamespace}/entries`;
    const entryPath = `${entriesPath}/${state.metadata.encodedKey}`;
    if (method === 'GET' && path === entriesPath) {
      const cursor = request.query.get('cursor');
      return respond.json(200, entryPageSchema.parse({
        items: [{ ...state.metadata, key: cursor === 'entry-cursor-2' ? 'next-page' : state.metadata.key }],
        nextCursor: cursor === null ? 'entry-cursor-2' : null,
        hasMore: cursor === null,
      }));
    }
    if (method === 'GET' && path === entryPath) return respond.json(200, state.metadata);
    if (method === 'POST' && path === `${entryPath}/value/reveal`) {
      return respond.json(200, revealedEntryValueSchema.parse({
        key: state.metadata.key, version: state.metadata.version,
        value: { type: 'STRING', text: 'sensitive <value>' },
        revealedAt: '2026-08-26T10:15:30Z', autoHideAfterMillis: state.autoHideAfterMillis,
      }), { 'cache-control': 'no-store', pragma: 'no-cache' });
    }
    if (method === 'PUT' && (path === entryPath || path.startsWith(`${entriesPath}/`))) {
      if (state.setEntryProblem !== undefined) return respond.problem(state.setEntryProblem.status, state.setEntryProblem.code, state.setEntryProblem.detail);
      const nextVersion = (BigInt(state.metadata.version) + 1n).toString();
      state.metadata = entryMetadataSchema.parse({ ...state.metadata, version: nextVersion, updatedAt: '2026-08-29T10:02:00Z' });
      return respond.json(200, entrySetResultSchema.parse({ applied: true, created: path !== entryPath, version: nextVersion, updatedAt: '2026-08-29T10:02:00Z', ttl: state.metadata.ttl }));
    }
    if (method === 'POST' && path === `${entryPath}/ttl`) {
      state.metadata = entryMetadataSchema.parse({ ...state.metadata, ttl: { state: 'EXPIRING', ttlMillis: 60_000, expiresAt: '2026-08-29T10:02:00Z' } });
      return respond.json(200, state.metadata);
    }
    if (method === 'POST' && path === `${entryPath}/persist`) {
      state.metadata = entryMetadataSchema.parse({ ...state.metadata, ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } });
      return respond.json(200, state.metadata);
    }
    if (method === 'POST' && path === `${entryPath}/touch`) {
      state.metadata = entryMetadataSchema.parse({ ...state.metadata, lastAccessedAt: '2026-08-29T10:01:30Z' });
      return respond.json(200, state.metadata);
    }
    if (method === 'DELETE' && path === entryPath) return respond.noContent();
    if (method === 'POST' && path === `${entriesPath}/bulk-delete/preview`) {
      return respond.json(200, bulkDeletePreviewSchema.parse({
        previewToken: 'p'.repeat(32), expiresAt: '2099-08-29T10:05:00Z', setupId: 'primary-cache', namespace: state.metadata.namespace,
        resolvedCount: '1', totalBytes: '5', sampleKeys: [state.metadata.key], confirmationPhrase: 'DELETE 1 ENTRIES',
      }));
    }
    if (method === 'POST' && path === `${entriesPath}/bulk-delete/execute`) {
      return respond.json(200, bulkDeleteResultSchema.parse({ processedCount: '1', deletedCount: '1', conflictCount: '0', missingCount: '0', failedCount: '0', conflicts: [] }));
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
