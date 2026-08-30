import { z } from 'zod';

import { bulkDeletePreviewSchema, bulkDeleteResultSchema } from './entry-administration-schemas';
import { ttlStateSchema } from './inspection-schemas';
import { utcInstantSchema } from './protocol-schemas';

const unsigned = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);
const signed = z.string().regex(/^-?(?:0|[1-9][0-9]*)$/u).refine((value) => {
  const parsed = BigInt(value);
  return parsed >= -9_223_372_036_854_775_808n && parsed <= 9_223_372_036_854_775_807n;
}, 'Value is outside signed 64-bit range');
const encoded = z.string().regex(/^[A-Za-z0-9_-]+$/u);

export const counterSchema = z.strictObject({
  namespace: z.string().min(1).max(128), encodedNamespace: encoded,
  key: z.string().min(1).max(1_024), encodedKey: encoded,
  value: signed, version: unsigned, createdAt: utcInstantSchema, updatedAt: utcInstantSchema,
  ttl: ttlStateSchema,
});

export const counterPageSchema = z.strictObject({
  items: z.array(counterSchema).max(200), nextCursor: z.string().min(1).max(4_096).nullable(), hasMore: z.boolean(),
}).superRefine((page, context) => { if (page.hasMore !== (page.nextCursor !== null)) context.addIssue({ code: 'custom', message: 'Counter cursor state is inconsistent' }); });

export const counterSetBodySchema = z.strictObject({
  value: signed, ttlMode: z.enum(['PRESERVE_EXISTING', 'REPLACE', 'REMOVE']), ttlMillis: z.number().int().positive().safe().nullable(),
}).superRefine((body, context) => { if ((body.ttlMode === 'REPLACE') !== (body.ttlMillis !== null)) context.addIssue({ code: 'custom', message: 'Counter TTL is inconsistent' }); });

export const counterAdjustBodySchema = z.strictObject({
  delta: signed.refine((value) => value !== '0', 'Delta cannot be zero'), createIfMissing: z.boolean(),
  ttlMode: z.enum(['PRESERVE_EXISTING', 'REPLACE', 'REMOVE']), ttlMillis: z.number().int().positive().safe().nullable(),
}).superRefine((body, context) => { if ((body.ttlMode === 'REPLACE') !== (body.ttlMillis !== null)) context.addIssue({ code: 'custom', message: 'Counter TTL is inconsistent' }); });

export const counterSelectionSchema = z.strictObject({ targets: z.array(z.strictObject({ namespace: z.string().min(1).max(128), key: z.string().min(1).max(1_024), version: unsigned })).min(1).max(1_000) });
export const confirmedCounterDeleteSchema = z.strictObject({ previewToken: z.string().min(32).max(512), confirmationPhrase: z.string().regex(/^DELETE [1-9][0-9]* COUNTERS$/u) });

export const lockSchema = z.strictObject({
  namespace: z.string().min(1).max(128), encodedNamespace: encoded,
  key: z.string().min(1).max(1_024), encodedKey: encoded,
  fencingToken: unsigned, version: unsigned, createdAt: utcInstantSchema, updatedAt: utcInstantSchema,
  leaseExpiresAt: utcInstantSchema, leaseRemainingMillis: z.number().int().nonnegative().safe(),
  owner: z.strictObject({ state: z.literal('MASKED') }),
});

export const lockPageSchema = z.strictObject({
  items: z.array(lockSchema).max(200), nextCursor: z.string().min(1).max(4_096).nullable(), hasMore: z.boolean(),
}).superRefine((page, context) => { if (page.hasMore !== (page.nextCursor !== null)) context.addIssue({ code: 'custom', message: 'Lock cursor state is inconsistent' }); });

export const revealedLockOwnerSchema = z.strictObject({ key: z.string().min(1).max(1_024), ownerToken: z.string().min(1), version: unsigned, revealedAt: utcInstantSchema, autoHideAfterMillis: z.number().int().positive().safe() });

export { bulkDeletePreviewSchema, bulkDeleteResultSchema };
export type Counter = z.infer<typeof counterSchema>;
export type CounterPage = z.infer<typeof counterPageSchema>;
export type CounterSetBody = z.infer<typeof counterSetBodySchema>;
export type CounterAdjustBody = z.infer<typeof counterAdjustBodySchema>;
export type CounterSelection = z.infer<typeof counterSelectionSchema>;
export type ConfirmedCounterDelete = z.infer<typeof confirmedCounterDeleteSchema>;
export type LockState = z.infer<typeof lockSchema>;
export type LockPage = z.infer<typeof lockPageSchema>;
export type RevealedLockOwner = z.infer<typeof revealedLockOwnerSchema>;
