import { z } from 'zod';

import type {
  AdminPageNamespaceStatsContract,
  NamespaceDetailsContract,
  NamespaceExportContract,
  NamespaceStatsContract,
  OverviewContract,
} from './openapi-contract';
import { utcInstantSchema } from './protocol-schemas';
import { setupHealthSchema } from './setup-schemas';

const nonNegativeDecimalSchema = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);

const availableLongValueSchema = z.discriminatedUnion('availability', [
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

export type Overview = z.infer<typeof overviewSchema>;
export type NamespaceStats = z.infer<typeof namespaceStatsSchema>;
export type NamespacePage = z.infer<typeof namespacePageSchema>;
export type NamespaceExport = z.infer<typeof namespaceExportSchema>;
export type NamespaceDetails = z.infer<typeof namespaceDetailsSchema>;
