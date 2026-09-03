import {
  publishAcceptedSchema, revealedPubSubPayloadSchema, subscriptionSummarySchema,
  type PublishAccepted, type RevealedPubSubPayload, type SubscriptionSummary,
} from './pubsub-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface PubSubClientPort {
  createSubscription(setupId: string, channel: string, bufferLimit: number): Promise<SubscriptionSummary>;
  publish(setupId: string, channel: string, payload: string, contentType?: string): Promise<PublishAccepted>;
  revealPayload(setupId: string, subscriptionId: string, messageId: string, reason?: string): Promise<RevealedPubSubPayload>;
  deleteSubscription(setupId: string, subscriptionId: string): Promise<void>;
}

export class PubSubClient implements PubSubClientPort {
  constructor(private readonly session: SessionClient) {}

  async createSubscription(setupId: string, channel: string, bufferLimit: number): Promise<SubscriptionSummary> {
    if (!Number.isSafeInteger(bufferLimit) || bufferLimit < 1 || bufferLimit > 500) invalid('Buffer limit must be between 1 and 500');
    return response(subscriptionSummarySchema, await this.session.requestJson(`${this.root(setupId)}/subscriptions`, {
      method: 'POST', body: { channel: validChannel(channel), bufferLimit },
    }));
  }

  async publish(setupId: string, channel: string, payload: string, contentType?: string): Promise<PublishAccepted> {
    const normalizedContentType = contentType?.trim();
    return response(publishAcceptedSchema, await this.session.requestJson(`${this.root(setupId)}/publish`, {
      method: 'POST', body: normalizedContentType === undefined || normalizedContentType === ''
        ? { channel: validChannel(channel), payload }
        : { channel: validChannel(channel), payload, contentType: normalizedContentType },
    }));
  }

  async revealPayload(setupId: string, subscriptionId: string, messageId: string, reason?: string): Promise<RevealedPubSubPayload> {
    const normalized = reason?.trim();
    if (normalized !== undefined && normalized !== '' && (normalized.length < 3 || normalized.length > 240)) invalid('Reveal reason must be between 3 and 240 characters');
    const body = normalized === undefined || normalized === '' ? {} : { reason: normalized };
    return response(revealedPubSubPayloadSchema, await this.session.requestSensitiveJson(`${this.root(setupId)}/subscriptions/${segment(subscriptionId)}/messages/${segment(messageId)}/payload/reveal`, { method: 'POST', body }));
  }

  async deleteSubscription(setupId: string, subscriptionId: string): Promise<void> {
    await this.session.requestJson(`${this.root(setupId)}/subscriptions/${segment(subscriptionId)}`, { method: 'DELETE' });
  }

  private root(setupId: string): string { return `/api/v1/setups/${segment(setupId)}/pubsub`; }
}

function response<T>(schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } }, value: unknown): T {
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned an incompatible Pub/Sub response');
  return parsed.data;
}
function validChannel(value: string): string { if (value.length < 1 || value.length > 63) invalid('Channel must be between 1 and 63 characters'); return value; }
function segment(value: string): string { return encodeURIComponent(value); }
function invalid(message: string): never { throw new ManagementClientError(400, 'VALIDATION_FAILED', message); }
