import { z } from 'zod';

const utcInstant = z.iso.datetime({ offset: true });
const channel = z.string().min(1).max(63);

export const subscriptionSummarySchema = z.object({
  subscriptionId: z.string().min(1).max(64),
  channel,
  streamPath: z.string().regex(/^\/api\/v1\//u),
  bufferLimit: z.number().int().min(1).max(500),
  createdAt: utcInstant,
  expiresAt: utcInstant,
}).strict();

export const publishAcceptedSchema = z.object({
  accepted: z.literal(true),
  publishedAt: utcInstant,
}).strict();

export const pubSubMessageMetadataSchema = z.object({
  messageId: z.string().min(1),
  channel,
  contentType: z.string().nullable(),
  payloadBytes: z.number().int().nonnegative(),
  receivedAt: utcInstant,
  payloadState: z.literal('MASKED'),
}).strict();

export const revealedPubSubPayloadSchema = z.object({
  messageId: z.string().min(1),
  channel,
  payload: z.string(),
  contentType: z.string().nullable(),
  encoding: z.literal('UTF8'),
  receivedAt: utcInstant,
  revealedAt: utcInstant,
  autoHideAfterMillis: z.number().int().positive(),
}).strict();

export type SubscriptionSummary = z.infer<typeof subscriptionSummarySchema>;
export type PublishAccepted = z.infer<typeof publishAcceptedSchema>;
export type PubSubMessageMetadata = z.infer<typeof pubSubMessageMetadataSchema>;
export type RevealedPubSubPayload = z.infer<typeof revealedPubSubPayloadSchema>;
