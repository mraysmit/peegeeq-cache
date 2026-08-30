import { z } from 'zod';

import { cacheValueSchema, entryMetadataSchema, ttlStateSchema } from './inspection-schemas';
import { utcInstantSchema } from './protocol-schemas';

const nonNegativeDecimalSchema = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);
const etagVersionSchema = nonNegativeDecimalSchema;

export const entrySetBodySchema = z.strictObject({
  value: cacheValueSchema,
  ttlMode: z.enum(['PRESERVE_EXISTING', 'USE_DEFAULT', 'REPLACE', 'REMOVE']),
  ttlMillis: z.number().int().positive().safe().nullable(),
  setMode: z.enum(['UPSERT', 'ONLY_IF_ABSENT', 'ONLY_IF_PRESENT', 'ONLY_IF_VERSION_MATCHES']),
}).superRefine((body, context) => {
  if ((body.ttlMode === 'REPLACE') !== (body.ttlMillis !== null)) {
    context.addIssue({ code: 'custom', message: 'Only replacement TTL requires ttlMillis' });
  }
});

export const entrySetResultSchema = z.strictObject({
  applied: z.literal(true),
  created: z.boolean(),
  version: etagVersionSchema,
  updatedAt: utcInstantSchema,
  ttl: ttlStateSchema,
});

const versionedKeyTargetSchema = z.strictObject({
  key: z.string().min(1).max(1_024),
  version: etagVersionSchema,
});

export const entryDeleteSelectionSchema = z.strictObject({
  selection: z.discriminatedUnion('type', [
    z.strictObject({
      type: z.literal('EXPLICIT'),
      targets: z.array(versionedKeyTargetSchema).min(1).max(1_000),
    }),
    z.strictObject({
      type: z.literal('FILTER'),
      prefix: z.string().max(1_024).optional(),
      valueType: z.enum(['STRING', 'JSON', 'LONG', 'BYTES']).optional(),
      ttlState: z.enum(['ALL_LIVE', 'PERSISTENT', 'EXPIRING', 'INCLUDE_EXPIRED']),
    }),
  ]),
});

export const bulkDeletePreviewSchema = z.strictObject({
  previewToken: z.string().min(32).max(512),
  expiresAt: utcInstantSchema,
  setupId: z.string().min(1).max(64),
  namespace: z.string().min(1).max(128).nullable(),
  resolvedCount: nonNegativeDecimalSchema,
  totalBytes: nonNegativeDecimalSchema,
  sampleKeys: z.array(z.string().min(1).max(1_024)).max(20),
  confirmationPhrase: z.string().min(1).max(512),
});

export const confirmedEntryDeleteSchema = z.strictObject({
  previewToken: z.string().min(32).max(512),
  confirmationPhrase: z.string().min(1).max(512),
});

export const bulkDeleteResultSchema = z.strictObject({
  processedCount: nonNegativeDecimalSchema,
  deletedCount: nonNegativeDecimalSchema,
  conflictCount: nonNegativeDecimalSchema,
  missingCount: nonNegativeDecimalSchema,
  failedCount: nonNegativeDecimalSchema,
  conflicts: z.array(z.strictObject({
    key: z.string().min(1).max(1_024),
    reason: z.enum(['VERSION_CHANGED', 'NOT_FOUND', 'FAILED']),
  })).max(1_000),
});

export { entryMetadataSchema };
export type EntrySetBody = z.infer<typeof entrySetBodySchema>;
export type EntrySetResult = z.infer<typeof entrySetResultSchema>;
export type EntryDeleteSelection = z.infer<typeof entryDeleteSelectionSchema>;
export type BulkDeletePreview = z.infer<typeof bulkDeletePreviewSchema>;
export type ConfirmedEntryDelete = z.infer<typeof confirmedEntryDeleteSchema>;
export type BulkDeleteResult = z.infer<typeof bulkDeleteResultSchema>;
