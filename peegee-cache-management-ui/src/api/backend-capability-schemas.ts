import { z } from 'zod';

import { utcInstantSchema } from './protocol-schemas';

const unsigned = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);
const signed = z.string().regex(/^-?(?:0|[1-9][0-9]*)$/u).refine((value) => {
  const parsed = BigInt(value);
  return parsed >= -9_223_372_036_854_775_808n && parsed <= 9_223_372_036_854_775_807n;
}, 'Value is outside signed 64-bit range');

export const coreCacheValueSchema = z.discriminatedUnion('type', [
  z.strictObject({ type: z.literal('STRING'), text: z.string() }),
  z.strictObject({ type: z.literal('JSON'), text: z.string() }),
  z.strictObject({ type: z.literal('LONG'), decimal: signed }),
  z.strictObject({ type: z.literal('BYTES'), base64: z.string() }),
]);

export const cacheKeyRequestSchema = z.strictObject({
  namespace: z.string().min(1).max(128),
  key: z.string().min(1).max(1_024),
});

export const cacheEntrySnapshotSchema = z.strictObject({
  namespace: z.string().min(1).max(128),
  key: z.string().min(1).max(1_024),
  version: unsigned,
  createdAt: utcInstantSchema,
  updatedAt: utcInstantSchema,
  expiresAt: utcInstantSchema.nullable(),
  hitCount: unsigned,
  lastAccessedAt: utcInstantSchema.nullable(),
  value: coreCacheValueSchema.nullable(),
});

export const entryExistsResultSchema = z.strictObject({ exists: z.boolean() });

export const batchGetRequestSchema = z.strictObject({
  keys: z.array(cacheKeyRequestSchema).min(1).max(1_000),
  reason: z.string().trim().min(3).max(240),
});
export const batchGetResultSchema = z.strictObject({
  items: z.array(z.strictObject({
    namespace: z.string().min(1).max(128),
    key: z.string().min(1).max(1_024),
    found: z.boolean(),
    entry: cacheEntrySnapshotSchema.nullable(),
  })).min(1).max(1_000),
});

export const coreCacheSetRequestSchema = z.strictObject({
  namespace: z.string().min(1).max(128),
  key: z.string().min(1).max(1_024),
  value: coreCacheValueSchema,
  ttlMillis: z.number().int().positive().safe().nullable(),
  setMode: z.enum(['UPSERT', 'ONLY_IF_ABSENT', 'ONLY_IF_PRESENT', 'ONLY_IF_VERSION_MATCHES']),
  expectedVersion: unsigned.nullable(),
  returnPreviousValue: z.boolean(),
}).superRefine((entry, context) => {
  if ((entry.setMode === 'ONLY_IF_VERSION_MATCHES') !== (entry.expectedVersion !== null)) {
    context.addIssue({ code: 'custom', message: 'Expected version must match the selected set mode' });
  }
});
export const batchSetRequestSchema = z.strictObject({
  entries: z.array(coreCacheSetRequestSchema).min(1).max(1_000),
});
export const batchSetResultSchema = z.strictObject({
  items: z.array(z.strictObject({
    namespace: z.string().min(1).max(128),
    key: z.string().min(1).max(1_024),
    applied: z.boolean(),
    newVersion: unsigned,
    previousEntry: cacheEntrySnapshotSchema.nullable(),
  })).min(1).max(1_000),
});
export const batchDeleteRequestSchema = z.strictObject({
  keys: z.array(cacheKeyRequestSchema).min(1).max(1_000),
});
export const batchDeleteResultSchema = z.strictObject({ deletedCount: unsigned });

export const scanEntriesRequestSchema = z.strictObject({
  namespace: z.string().min(1).max(128),
  prefix: z.string().max(1_024).nullable(),
  cursor: z.string().max(4_096).nullable(),
  limit: z.number().int().min(1).max(200),
  includeValues: z.boolean(),
  includeExpired: z.boolean(),
  reason: z.string().trim().min(3).max(240),
});
export const scanEntriesResultSchema = z.strictObject({
  entries: z.array(cacheEntrySnapshotSchema).max(200),
  nextCursor: z.string().max(4_096).nullable(),
  hasMore: z.boolean(),
}).superRefine((page, context) => {
  if (page.hasMore !== (page.nextCursor !== null)) {
    context.addIssue({ code: 'custom', message: 'Scan cursor state is inconsistent' });
  }
});

export const acquireLockRequestSchema = z.strictObject({
  ownerToken: z.string().min(1).max(4_096),
  leaseTtlMillis: z.number().int().positive().safe(),
  reentrantForSameOwner: z.boolean(),
  issueFencingToken: z.boolean(),
});
export const acquireLockResultSchema = z.strictObject({
  acquired: z.boolean(),
  namespace: z.string().min(1).max(128),
  key: z.string().min(1).max(1_024),
  ownerToken: z.string().max(4_096).nullable(),
  fencingToken: unsigned.nullable(),
  leaseExpiresAt: utcInstantSchema.nullable(),
});
export const renewLockRequestSchema = z.strictObject({
  ownerToken: z.string().min(1).max(4_096),
  leaseTtlMillis: z.number().int().positive().safe(),
});
export const lockOwnerRequestSchema = z.strictObject({
  ownerToken: z.string().min(1).max(4_096),
});
export const renewLockResultSchema = z.strictObject({ renewed: z.boolean() });
export const releaseLockResultSchema = z.strictObject({ released: z.boolean() });
export const lockOwnershipResultSchema = z.strictObject({ heldByOwner: z.boolean() });

export const cacheMetricsSnapshotSchema = z.strictObject({
  cacheGets: unsigned,
  cacheHits: unsigned,
  cacheMisses: unsigned,
  cacheSets: unsigned,
  cacheSetsApplied: unsigned,
  cacheDeletes: unsigned,
  counterIncrements: unsigned,
  counterSets: unsigned,
  counterDeletes: unsigned,
  lockAcquires: unsigned,
  lockAcquiresGranted: unsigned,
  lockRenewals: unsigned,
  lockReleases: unsigned,
  publishes: unsigned,
  subscribes: unsigned,
});

export type CoreCacheValue = z.infer<typeof coreCacheValueSchema>;
export type CacheEntrySnapshot = z.infer<typeof cacheEntrySnapshotSchema>;
export type CacheKeyRequest = z.infer<typeof cacheKeyRequestSchema>;
export type BatchGetRequest = z.infer<typeof batchGetRequestSchema>;
export type BatchGetResult = z.infer<typeof batchGetResultSchema>;
export type CoreCacheSetRequest = z.infer<typeof coreCacheSetRequestSchema>;
export type BatchSetRequest = z.infer<typeof batchSetRequestSchema>;
export type BatchSetResult = z.infer<typeof batchSetResultSchema>;
export type BatchDeleteRequest = z.infer<typeof batchDeleteRequestSchema>;
export type BatchDeleteResult = z.infer<typeof batchDeleteResultSchema>;
export type ScanEntriesRequest = z.infer<typeof scanEntriesRequestSchema>;
export type ScanEntriesResult = z.infer<typeof scanEntriesResultSchema>;
export type AcquireLockRequest = z.infer<typeof acquireLockRequestSchema>;
export type AcquireLockResult = z.infer<typeof acquireLockResultSchema>;
export type RenewLockRequest = z.infer<typeof renewLockRequestSchema>;
export type CacheMetricsSnapshot = z.infer<typeof cacheMetricsSnapshotSchema>;
