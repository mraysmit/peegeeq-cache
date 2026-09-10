import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { currentSessionSchema } from '@src/api/protocol-schemas';
import { pubSubMessageMetadataSchema, publishAcceptedSchema, revealedPubSubPayloadSchema, subscriptionSummarySchema } from '@src/api/pubsub-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { PubSubPage } from '@src/features/pubsub/PubSubPage';
import { useLiveStore } from '@src/state/live-store';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';
import { renderWithProviders } from './support/render';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'pubsub-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'pubsub-page-csrf-token-with-forty-three-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const STREAM_PATH = '/api/v1/setups/primary-cache/pubsub/subscriptions/sub-1/stream';
const subscription = subscriptionSummarySchema.parse({
  subscriptionId: 'sub-1', channel: 'orders', streamPath: STREAM_PATH, bufferLimit: 20, createdAt: '2026-08-29T10:00:00Z', expiresAt: '2099-08-29T10:00:00Z',
});
const message = pubSubMessageMetadataSchema.parse({ messageId: 'm1', channel: 'orders', contentType: 'application/json', payloadBytes: 14, receivedAt: '2026-08-29T10:01:00Z', payloadState: 'MASKED' });
const frame = (metadata: unknown, id: string) => `id: ${id}\nevent: pubsub.message\ndata: ${JSON.stringify(metadata)}\n\n`;

describe('U7 Pub/Sub page', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let stream: { opened: number; closed: number };
  let streamFailuresRemaining: number;
  let contentType: string | null = 'application/json';

  beforeEach(async () => {
    localStorage.clear();
    // One counter object per server: a response destroyed by the previous fixture's close()
    // emits its 'close' event asynchronously and must not be counted against this test.
    const counters = { opened: 0, closed: 0 };
    stream = counters;
    streamFailuresRemaining = 0;
    contentType = 'application/json';
    useLiveStore.getState().stopPubSub();
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
      if (route('POST', '/api/v1/setups/primary-cache/pubsub/subscriptions', request)) {
        const { channel, bufferLimit } = request.body as { channel: string; bufferLimit: number };
        return respond.json(201, subscriptionSummarySchema.parse({ ...subscription, channel, bufferLimit }));
      }
      if (route('GET', STREAM_PATH, request)) {
        if (streamFailuresRemaining > 0) {
          streamFailuresRemaining -= 1;
          return respond.problem(503, 'STREAM_UNAVAILABLE', 'the first Pub/Sub handshake is unavailable');
        }
        counters.opened += 1;
        request.onClose(() => { counters.closed += 1; });
        return respond.sse([frame(pubSubMessageMetadataSchema.parse({ ...message, contentType }), '1')], { keepOpen: true });
      }
      if (route('DELETE', '/api/v1/setups/primary-cache/pubsub/subscriptions/sub-1', request)) return respond.noContent();
      if (route('POST', '/api/v1/setups/primary-cache/pubsub/publish', request)) return respond.json(202, publishAcceptedSchema.parse({ accepted: true, publishedAt: '2026-08-29T10:01:00Z' }));
      if (route('POST', '/api/v1/setups/primary-cache/pubsub/subscriptions/sub-1/messages/m1/payload/reveal', request)) {
        return respond.json(200, revealedPubSubPayloadSchema.parse({
          messageId: 'm1', channel: 'orders', payload: '<secret>&value', contentType: null, encoding: 'UTF8',
          receivedAt: '2026-08-29T10:01:00Z', revealedAt: '2026-08-29T10:01:01Z', autoHideAfterMillis: 60_000,
        }), { 'cache-control': 'no-store', pragma: 'no-cache' });
      }
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    store = createManagementStore(createManagementClients(sessionClient));
  });

  afterEach(async () => {
    sessionClient.clear();
    await server.close();
  });

  const render = (props: Partial<Parameters<typeof PubSubPage>[0]> = {}) => renderWithProviders(
    <PubSubPage canOperate canReveal maximumChannelBytes={63} maximumPayloadBytes={7_500} selectedSetupId="primary-cache" {...props} />,
    { store },
  );
  const requests = (method: string, suffix: string) => server.requests.filter((request) => request.method === method && request.path.endsWith(suffix));

  it('allows viewer subscription metadata while withholding publish and reveal operations', async () => {
    const user = userEvent.setup();
    streamFailuresRemaining = 1;
    render({ canOperate: false, canReveal: false });
    expect(screen.queryByRole('heading', { name: 'Publish' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Buffer entries')).toHaveValue(20);
    await user.type(screen.getByLabelText('Channel', { exact: true }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Start subscription' }));

    const region = await screen.findByRole('region', { name: 'Subscription: orders' });
    await waitFor(() => expect(region).toHaveTextContent('CONNECTED'), { timeout: 5_000 });
    expect(screen.queryByText('Live Pub/Sub connection was interrupted')).not.toBeInTheDocument();
    expect(await screen.findByText('Masked', undefined, { timeout: 5_000 })).toBeVisible();
    expect(screen.getByText('application/json')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Retained Pub/Sub messages' })).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: /Reveal payload/u })).not.toBeInTheDocument();
    expect(requests('POST', '/pubsub/subscriptions')[0]!.body).toEqual({ channel: 'orders', bufferLimit: 20 });
    expect(requests('GET', '/stream')[0]!.headers.accept).toBe('text/event-stream');
    expect(useLiveStore.getState().pubSubMessages).toEqual([message]);
  });

  it('labels subscriptions non-durable, keeps metadata masked, reveals only on demand, and stops the stream', async () => {
    const user = userEvent.setup();
    contentType = null;
    const view = render();
    await user.type(screen.getByLabelText('Channel', { exact: true }), 'orders');
    await user.click(screen.getByRole('button', { name: 'Start subscription' }));
    const region = await screen.findByRole('region', { name: 'Subscription: orders' });
    expect(region).toHaveTextContent('Non-durable');
    expect(region).toHaveTextContent('bounded to 20 messages');
    expect(await screen.findByRole('status')).toHaveTextContent(/non-durable/iu);
    expect(await screen.findByText('Masked', undefined, { timeout: 5_000 })).toBeVisible();
    expect(screen.getByText('Not supplied')).toBeVisible();
    expect(document.body).not.toHaveTextContent('<secret>&value');

    await user.click(screen.getByRole('button', { name: 'Reveal payload m1' }));
    expect(await screen.findByText('<secret>&value')).toBeVisible();
    expect(requests('POST', '/payload/reveal')).toHaveLength(1);
    expect(JSON.stringify(store.getState())).not.toContain('<secret>&value');
    expect(JSON.stringify(useLiveStore.getState())).not.toContain('<secret>&value');
    await user.click(screen.getByRole('button', { name: 'Hide payload' }));
    expect(document.body).not.toHaveTextContent('<secret>&value');

    await user.click(screen.getByRole('button', { name: 'Reveal payload m1' }));
    await screen.findByText('<secret>&value');
    let hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    act(() => { document.dispatchEvent(new globalThis.Event('visibilitychange')); });
    expect(document.body).not.toHaveTextContent('<secret>&value');
    hidden = false;

    await user.click(screen.getByRole('button', { name: 'Reveal payload m1' }));
    await screen.findByText('<secret>&value');
    await user.click(screen.getByRole('button', { name: 'Stop subscription' }));
    expect(await screen.findByRole('heading', { name: 'Subscribe' })).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('discarded');
    expect(document.body).not.toHaveTextContent('<secret>&value');
    expect(screen.queryByText('No retained message metadata.')).not.toBeInTheDocument();
    expect(requests('DELETE', '/subscriptions/sub-1')).toHaveLength(1);
    await waitFor(() => expect(stream.closed).toBe(stream.opened));
    expect(stream.opened).toBe(1);
    expect(useLiveStore.getState().pubSubMessages).toEqual([]);
    expect(useLiveStore.getState().pubSubConnection).toBe('STOPPED');
    view.unmount();
  });

  it('enforces UTF-8 payload bytes and describes publish as acceptance, never delivery', async () => {
    const user = userEvent.setup();
    render({ maximumPayloadBytes: 4 });
    await user.type(screen.getByLabelText('Publish channel'), 'orders');
    await user.type(screen.getByLabelText('Payload'), '€€');
    expect(screen.getByText('6 raw · 6 / 4 wire bytes')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    await user.clear(screen.getByLabelText('Payload'));
    await user.type(screen.getByLabelText('Payload'), 'ok');
    await user.click(screen.getByRole('button', { name: 'Publish' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Accepted by PostgreSQL');
    expect(screen.getByRole('status')).not.toHaveTextContent(/deliver/i);
    expect(screen.getByLabelText('Payload')).toHaveValue('');
    expect(requests('POST', '/pubsub/publish')[0]!.body).toMatchObject({ channel: 'orders', payload: 'ok' });
    expect(requests('POST', '/pubsub/publish')[0]!.headers['x-peegeeq-csrf']).toBe('pubsub-page-csrf-token-with-forty-three-characters');
  });

  it('enforces subscription and publish channel limits as UTF-8 bytes', async () => {
    const user = userEvent.setup();
    render({ maximumChannelBytes: 4, maximumPayloadBytes: 100 });

    await user.type(screen.getByLabelText('Channel', { exact: true }), 'ééé');
    expect(screen.getByText('6 / 4 UTF-8 bytes')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start subscription' })).toBeDisabled();
    await user.clear(screen.getByLabelText('Channel', { exact: true }));
    await user.type(screen.getByLabelText('Channel', { exact: true }), 'éé');
    expect(screen.getByRole('button', { name: 'Start subscription' })).toBeEnabled();
    await user.clear(screen.getByLabelText('Buffer entries'));
    await user.type(screen.getByLabelText('Buffer entries'), '501');
    expect(screen.getByRole('button', { name: 'Start subscription' })).toBeDisabled();

    await user.type(screen.getByLabelText('Publish channel'), 'ééé');
    expect(screen.getByText('6 / 4 channel UTF-8 bytes')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    expect(within(screen.getByRole('region', { name: 'Publish' })).getByLabelText('Content type (optional)')).toHaveAttribute('placeholder', 'application/json');
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });
});
