import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { PubSubClientPort } from '@src/api/pubsub-client';
import type { PubSubMessageMetadata, RevealedPubSubPayload } from '@src/api/pubsub-schemas';
import type { LiveStream, PubSubStreamPort } from '@src/api/live-transport';
import { PubSubPage } from '@src/features/pubsub/PubSubPage';

class PubSubFake implements PubSubClientPort {
  published: string[] = [];
  stopped = false;
  async createSubscription() { return { subscriptionId: 'sub-1', channel: 'orders', streamPath: '/stream', bufferLimit: 20, createdAt: '2026-08-29T10:00:00Z', expiresAt: '2099-08-29T10:00:00Z' }; }
  async publish(_setup: string, _channel: string, payload: string) { this.published.push(payload); return { accepted: true as const, publishedAt: '2026-08-29T10:01:00Z' }; }
  async revealPayload(): Promise<RevealedPubSubPayload> { return { messageId: 'm1', channel: 'orders', payload: '<secret>&value', contentType: null, encoding: 'UTF8', receivedAt: '2026-08-29T10:01:00Z', revealedAt: '2026-08-29T10:01:01Z', autoHideAfterMillis: 60_000 }; }
  async deleteSubscription() { this.stopped = true; }
}

class StreamFake implements PubSubStreamPort {
  message?: (message: PubSubMessageMetadata) => void;
  connect(_path: string, handlers: Parameters<PubSubStreamPort['connect']>[1]): LiveStream { this.message = handlers.onMessage; return { stop() {} }; }
}

describe('U7 Pub/Sub page', () => {
  it('allows viewer subscription metadata while withholding publish and reveal operations', async () => {
    const stream = new StreamFake(); const user = userEvent.setup();
    render(<PubSubPage canOperate={false} canReveal={false} client={new PubSubFake()} maximumChannelBytes={63} maximumPayloadBytes={7_500} selectedSetupId="primary-cache" stream={stream} />);
    expect(screen.queryByRole('heading', { name: 'Publish' })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Channel'), 'orders');
    await user.click(screen.getByRole('button', { name: 'Start subscription' }));
    stream.message?.({ messageId: 'm1', channel: 'orders', contentType: null, payloadBytes: 14, receivedAt: '2026-08-29T10:01:00Z', payloadState: 'MASKED' });
    expect(await screen.findByText('Masked')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Retained Pub/Sub messages' })).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: /Reveal payload/u })).not.toBeInTheDocument();
  });

  it('labels subscriptions non-durable, keeps metadata masked, and reveals only on demand', async () => {
    const client = new PubSubFake(); const stream = new StreamFake(); const user = userEvent.setup();
    render(<PubSubPage canOperate canReveal client={client} maximumChannelBytes={63} maximumPayloadBytes={7_500} selectedSetupId="primary-cache" stream={stream} />);
    await user.type(screen.getByLabelText('Channel'), 'orders');
    await user.click(screen.getByRole('button', { name: 'Start subscription' }));
    expect((await screen.findAllByText(/non-durable/i)).length).toBeGreaterThan(0);
    stream.message?.({ messageId: 'm1', channel: 'orders', contentType: null, payloadBytes: 14, receivedAt: '2026-08-29T10:01:00Z', payloadState: 'MASKED' });
    expect(await screen.findByText('Masked')).toBeVisible();
    expect(document.body).not.toHaveTextContent('<secret>&value');
    await user.click(screen.getByRole('button', { name: 'Reveal payload m1' }));
    expect(await screen.findByText('<secret>&value')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Hide payload' }));
    expect(document.body).not.toHaveTextContent('<secret>&value');
  });

  it('enforces UTF-8 payload bytes and describes publish as acceptance, never delivery', async () => {
    const client = new PubSubFake(); const user = userEvent.setup();
    render(<PubSubPage canOperate canReveal client={client} maximumChannelBytes={63} maximumPayloadBytes={4} selectedSetupId="primary-cache" stream={new StreamFake()} />);
    await user.type(screen.getByLabelText('Publish channel'), 'orders');
    await user.type(screen.getByLabelText('Payload'), '€€');
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    await user.clear(screen.getByLabelText('Payload')); await user.type(screen.getByLabelText('Payload'), 'ok');
    await user.click(screen.getByRole('button', { name: 'Publish' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Accepted by PostgreSQL');
    expect(screen.getByRole('status')).not.toHaveTextContent(/deliver/i);
    expect(client.published).toEqual(['ok']);
  });

  it('enforces subscription and publish channel limits as UTF-8 bytes', async () => {
    const user = userEvent.setup();
    render(<PubSubPage canOperate canReveal client={new PubSubFake()} maximumChannelBytes={4} maximumPayloadBytes={100} selectedSetupId="primary-cache" stream={new StreamFake()} />);

    await user.type(screen.getByLabelText('Channel', { exact: true }), 'ééé');
    expect(screen.getByText('6 / 4 UTF-8 bytes')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start subscription' })).toBeDisabled();
    await user.clear(screen.getByLabelText('Channel', { exact: true }));
    await user.type(screen.getByLabelText('Channel', { exact: true }), 'éé');
    expect(screen.getByRole('button', { name: 'Start subscription' })).toBeEnabled();

    await user.type(screen.getByLabelText('Publish channel'), 'ééé');
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
  });
});
