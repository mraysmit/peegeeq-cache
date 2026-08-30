import { z } from 'zod';

import type {
  ActivityPageContract,
  AdminPageManagementEntryMetadataContract,
  AdminPageNamespaceStatsContract,
  DatabaseMonitoringContract,
  ManagementEntryMetadataContract,
  NamespaceDetailsContract,
  NamespaceExportContract,
  NamespaceStatsContract,
  OverviewContract,
  RuntimeMonitoringContract,
  CacheValueContract,
  RevealedEntryValueContract,
} from './openapi-contract';
import { utcInstantSchema } from './protocol-schemas';
import { setupHealthSchema } from './setup-schemas';

const nonNegativeDecimalSchema = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);
const signedDecimalSchema = z.string().regex(/^-?(?:0|[1-9][0-9]*)$/u);

export const availableLongValueSchema = z.discriminatedUnion('availability', [
  z.strictObject({
    availability: z.literal('AVAILABLE'),
    reason: z.null(),
    value: nonNegativeDecimalSchema,
  }),
  z.strictObject({
    availability: z.literal('UNAVAILABLE'),
    reason: z.string().min(1).max(256),
    value: z.null(),
  }),
]);

export const namespaceStatsSchema: z.ZodType<NamespaceStatsContract> = z.strictObject({
  namespace: z.string().min(1).max(128),
  encodedNamespace: z.string().regex(/^[A-Za-z0-9_-]+$/u),
  liveEntryCount: nonNegativeDecimalSchema,
  liveCounterCount: nonNegativeDecimalSchema,
  activeLockCount: nonNegativeDecimalSchema,
  expiringEntryCount: nonNegativeDecimalSchema,
  expiredEntryCount: nonNegativeDecimalSchema,
  estimatedStorageBytes: nonNegativeDecimalSchema,
  observedAt: utcInstantSchema,
});

const valueTypeCountsSchema = z.partialRecord(
  z.enum(['STRING', 'JSON', 'LONG', 'BYTES']),
  nonNegativeDecimalSchema,
);

export const overviewSchema: z.ZodType<OverviewContract> = z.strictObject({
  scope: z.literal('DATABASE'),
  observedAt: utcInstantSchema,
  health: setupHealthSchema,
  totals: z.strictObject({
    namespaceCount: nonNegativeDecimalSchema,
    liveEntryCount: nonNegativeDecimalSchema,
    liveCounterCount: nonNegativeDecimalSchema,
    activeLockCount: nonNegativeDecimalSchema,
    expiredEntryCount: nonNegativeDecimalSchema,
    schemaBytes: availableLongValueSchema,
  }),
  expiry: z.strictObject({
    oldestExpiredRowLagMillis: z.number().int().nonnegative().nullable(),
    sweeperEnabled: z.boolean(),
    lastSweepAt: utcInstantSchema.nullable(),
    lastSweepDeletedRows: nonNegativeDecimalSchema,
  }),
  valueTypeCounts: valueTypeCountsSchema,
  topNamespaces: z.array(namespaceStatsSchema).max(20),
});

export const namespacePageSchema: z.ZodType<AdminPageNamespaceStatsContract> = z.strictObject({
  items: z.array(namespaceStatsSchema).max(200),
  nextCursor: z.string().min(1).max(4_096).nullable(),
  hasMore: z.boolean(),
}).superRefine((page, context) => {
  if (page.hasMore !== (page.nextCursor !== null)) {
    context.addIssue({ code: 'custom', message: 'Cursor state is inconsistent' });
  }
});

export const namespaceExportSchema: z.ZodType<NamespaceExportContract> = z.strictObject({
  items: z.array(namespaceStatsSchema).max(10_000),
  truncated: z.boolean(),
  exportedAt: utcInstantSchema,
});

export const namespaceDetailsSchema: z.ZodType<NamespaceDetailsContract> = z.strictObject({
  stats: namespaceStatsSchema,
  valueTypeCounts: valueTypeCountsSchema,
  ttlDistribution: z.array(z.strictObject({
    range: z.enum([
      'PERSISTENT',
      'EXPIRED',
      'LT_1_MINUTE',
      'FROM_1_TO_5_MINUTES',
      'FROM_5_TO_30_MINUTES',
      'FROM_30_TO_60_MINUTES',
      'GTE_60_MINUTES',
    ]),
    count: nonNegativeDecimalSchema,
  })).max(7),
});

export const databaseMonitoringSchema: z.ZodType<DatabaseMonitoringContract> = z.strictObject({
  scope: z.literal('DATABASE'),
  observedAt: utcInstantSchema,
  health: setupHealthSchema,
  tableBytes: availableLongValueSchema,
  indexBytes: availableLongValueSchema,
  schemaBytes: availableLongValueSchema,
  liveRows: availableLongValueSchema,
  expiredRows: availableLongValueSchema,
  deadTuples: availableLongValueSchema,
  lastVacuumAt: utcInstantSchema.nullable(),
  lastAutovacuumAt: utcInstantSchema.nullable(),
  databaseConnections: availableLongValueSchema,
  cacheConnections: availableLongValueSchema,
  expiryBacklog: nonNegativeDecimalSchema,
  oldestExpiredRowLagMillis: z.number().int().nonnegative().nullable(),
});

const runtimePoolStateSchema = z.strictObject({
  active: availableLongValueSchema,
  idle: availableLongValueSchema,
  pending: availableLongValueSchema,
  maximum: availableLongValueSchema,
});

export const runtimeMonitoringSchema: z.ZodType<RuntimeMonitoringContract> = z.strictObject({
  scope: z.literal('MANAGEMENT_RUNTIME'),
  observedAt: utcInstantSchema,
  lifecycleState: z.enum(['NEW', 'STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'FAILED']),
  pool: runtimePoolStateSchema,
  activeOperations: nonNegativeDecimalSchema,
  pubSubSubscriptions: nonNegativeDecimalSchema,
  sseClients: nonNegativeDecimalSchema,
  webSocketClients: nonNegativeDecimalSchema,
  retainedPayloadBytes: nonNegativeDecimalSchema,
  auditQueue: z.strictObject({
    depth: nonNegativeDecimalSchema,
    capacity: nonNegativeDecimalSchema,
    acceptingMutations: z.boolean(),
  }),
  expirySweeper: z.strictObject({
    ownedByRuntime: z.boolean(),
    running: z.boolean(),
    lastSweepAt: utcInstantSchema.nullable(),
  }),
  operations: z.array(z.strictObject({
    operation: z.string().min(1).max(128),
    status: z.enum(['ACTIVE', 'COMPLETE', 'FAILED']),
    count: nonNegativeDecimalSchema,
    errorCount: nonNegativeDecimalSchema,
    latencyMillis: nonNegativeDecimalSchema,
  })).max(256),
});

export const activityEventSchema = z.strictObject({
  eventId: z.string().min(1).max(64),
  occurredAt: utcInstantSchema,
  actor: z.string().min(1).max(256),
  action: z.string().min(1).max(128),
  outcome: z.enum(['SUCCEEDED', 'REJECTED', 'FAILED', 'UNKNOWN']),
  setupId: z.string().min(1).max(64),
  namespace: z.string().min(1).max(128).nullable(),
  resource: z.strictObject({
    type: z.enum(['SETUP', 'CACHE_ENTRY', 'COUNTER', 'LOCK', 'SUBSCRIPTION', 'PUBSUB_MESSAGE']),
    identifier: z.string().max(1024).nullable(),
  }),
  summary: z.string().max(512),
  correlationId: z.string().min(1).max(128),
});

export const activityPageSchema: z.ZodType<ActivityPageContract> = z.strictObject({
  items: z.array(activityEventSchema).max(200),
  nextAfter: z.string().min(1).max(128).nullable(),
  hasMore: z.boolean(),
}).superRefine((page, context) => {
  if (page.hasMore !== (page.nextAfter !== null)) {
    context.addIssue({ code: 'custom', message: 'Activity cursor state is inconsistent' });
  }
});

export const ttlStateSchema = z.strictObject({
  state: z.enum(['PERSISTENT', 'EXPIRING', 'EXPIRED']),
  ttlMillis: z.number().int().nonnegative().nullable(),
  expiresAt: utcInstantSchema.nullable(),
}).superRefine((ttl, context) => {
  const persistent = ttl.state === 'PERSISTENT';
  if (persistent !== (ttl.ttlMillis === null && ttl.expiresAt === null)) {
    context.addIssue({ code: 'custom', message: 'TTL state is inconsistent' });
  }
  if (!persistent && (ttl.ttlMillis === null || ttl.expiresAt === null)) {
    context.addIssue({ code: 'custom', message: 'Expiring TTL requires duration and expiry' });
  }
});

export const entryMetadataSchema: z.ZodType<ManagementEntryMetadataContract> = z.strictObject({
  namespace: z.string().min(1).max(128),
  encodedNamespace: z.string().regex(/^[A-Za-z0-9_-]+$/u),
  key: z.string().min(1).max(1_024),
  encodedKey: z.string().regex(/^[A-Za-z0-9_-]+$/u),
  valueType: z.enum(['STRING', 'JSON', 'LONG', 'BYTES']),
  sizeBytes: nonNegativeDecimalSchema,
  version: nonNegativeDecimalSchema,
  createdAt: utcInstantSchema,
  updatedAt: utcInstantSchema,
  lastAccessedAt: utcInstantSchema.nullable(),
  ttl: ttlStateSchema,
});

export const entryPageSchema: z.ZodType<AdminPageManagementEntryMetadataContract> = z.strictObject({
  items: z.array(entryMetadataSchema).max(200),
  nextCursor: z.string().min(1).max(4_096).nullable(),
  hasMore: z.boolean(),
}).superRefine((page, context) => {
  if (page.hasMore !== (page.nextCursor !== null)) {
    context.addIssue({ code: 'custom', message: 'Entry cursor state is inconsistent' });
  }
});

const base64Schema = z.string().refine((value) => (
  value.length % 4 === 0
  && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(value)
), 'Invalid Base64 value');

export const cacheValueSchema: z.ZodType<CacheValueContract> = z.discriminatedUnion('type', [
  z.strictObject({ type: z.literal('STRING'), text: z.string() }),
  z.strictObject({ type: z.literal('JSON'), text: z.string() }),
  z.strictObject({ type: z.literal('LONG'), decimal: signedDecimalSchema }),
  z.strictObject({ type: z.literal('BYTES'), base64: base64Schema }),
]);

export const revealedEntryValueSchema: z.ZodType<RevealedEntryValueContract> = z.strictObject({
  key: z.string().min(1).max(1_024),
  version: nonNegativeDecimalSchema,
  value: cacheValueSchema,
  revealedAt: utcInstantSchema,
  autoHideAfterMillis: z.number().int().positive().safe(),
});

export type Overview = z.infer<typeof overviewSchema>;
export type NamespaceStats = z.infer<typeof namespaceStatsSchema>;
export type NamespacePage = z.infer<typeof namespacePageSchema>;
export type NamespaceExport = z.infer<typeof namespaceExportSchema>;
export type NamespaceDetails = z.infer<typeof namespaceDetailsSchema>;
export type DatabaseMonitoring = z.infer<typeof databaseMonitoringSchema>;
export type RuntimeMonitoring = z.infer<typeof runtimeMonitoringSchema>;
export type ActivityPage = z.infer<typeof activityPageSchema>;
export type EntryMetadata = z.infer<typeof entryMetadataSchema>;
export type EntryPage = z.infer<typeof entryPageSchema>;
export type CacheValue = z.infer<typeof cacheValueSchema>;
export type RevealedEntryValue = z.infer<typeof revealedEntryValueSchema>;
