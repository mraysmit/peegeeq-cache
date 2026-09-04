import { Alert, Button, Card, Empty, Form, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';

import type { PubSubMessageMetadata, RevealedPubSubPayload, SubscriptionSummary } from '../../api/pubsub-schemas';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';
import { useLiveStore } from '../../state/live-store';
import { effectiveAutoHideMillis } from '../../state/preferences';
import { useManagementClients } from '../../store';
import { isManagementQueryError, toQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { useCreateSubscriptionMutation, useDeleteSubscriptionMutation, usePublishMessageMutation } from '../../store/api/pubSubApi';

const { Title, Text, Paragraph } = Typography;

interface PubSubPageProps {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly maximumChannelBytes: number;
  readonly maximumPayloadBytes: number;
  readonly selectedSetupId?: string;
}

/**
 * Pub/Sub. Subscription creation, deletion, and publish are RTK Query mutations; the live message
 * stream is an SSE transport whose connection state and bounded metadata buffer live in the
 * Zustand live store (design §8.2). Payload reveal is sensitive: it goes through the no-store
 * client and is held only in this component's state with auto-hide.
 */
export function PubSubPage({ canOperate, canReveal, maximumChannelBytes, maximumPayloadBytes, selectedSetupId }: PubSubPageProps) {
  const clients = useManagementClients();
  const connection = useLiveStore((state) => state.pubSubConnection);
  const messages = useLiveStore((state) => state.pubSubMessages);
  const setConnection = useLiveStore((state) => state.setPubSubConnection);
  const receiveMessage = useLiveStore((state) => state.receivePubSubMessage);
  const clearMessages = useLiveStore((state) => state.clearPubSubMessages);
  const stopLive = useLiveStore((state) => state.stopPubSub);
  const [createSubscription] = useCreateSubscriptionMutation();
  const [deleteSubscription] = useDeleteSubscriptionMutation();
  const [publishMessage] = usePublishMessageMutation();

  const [channel, setChannel] = useState('');
  const [bufferLimit, setBufferLimit] = useState('20');
  const [subscription, setSubscription] = useState<SubscriptionSummary>();
  const [revealed, setRevealed] = useState<RevealedPubSubPayload>();
  const [publishChannel, setPublishChannel] = useState('');
  const [payload, setPayload] = useState('');
  const [contentType, setContentType] = useState('');
  const [status, setStatus] = useState('');
  const [problem, setProblem] = useState<ManagementQueryError>();
  const [busy, setBusy] = useState(false);

  const channelBytes = utf8Bytes(channel);
  const publishChannelBytes = utf8Bytes(publishChannel);
  const payloadBytes = utf8Bytes(payload);
  const wirePayloadBytes = encodedPayloadBytes(payload, contentType);
  const bufferEntries = Number(bufferLimit);
  const bufferValid = Number.isSafeInteger(bufferEntries) && bufferEntries >= 1 && bufferEntries <= 500;

  useEffect(() => {
    if (subscription === undefined) return undefined;
    const live = clients.pubSubStream.connect(`${clients.origin}${subscription.streamPath}`, {
      onMessage: (message) => receiveMessage(message, subscription.bufferLimit),
      onState: setConnection,
      onReset: (reason) => { clearMessages(); setStatus(reason); },
      onError: (message) => setStatus(message),
    });
    return () => { live.stop(); stopLive(); };
  }, [clearMessages, clients, receiveMessage, setConnection, stopLive, subscription]);

  useEffect(() => {
    if (revealed === undefined) return undefined;
    const timer = window.setTimeout(() => setRevealed(undefined), effectiveAutoHideMillis(revealed.autoHideAfterMillis));
    return () => window.clearTimeout(timer);
  }, [revealed]);

  useEffect(() => {
    const hideWhenBackgrounded = () => { if (document.hidden) setRevealed(undefined); };
    document.addEventListener('visibilitychange', hideWhenBackgrounded);
    return () => document.removeEventListener('visibilitychange', hideWhenBackgrounded);
  }, []);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setProblem(undefined);
    try {
      await operation();
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
    } finally {
      setBusy(false);
    }
  };

  const start = () => {
    if (selectedSetupId === undefined) return;
    void run(async () => {
      const created = await createSubscription({ setupId: selectedSetupId, channel, bufferLimit: bufferEntries }).unwrap();
      clearMessages();
      setSubscription(created);
      setStatus('Non-durable console subscription started. Messages are retained only in a bounded process-local buffer.');
    });
  };

  const stop = () => {
    if (selectedSetupId === undefined || subscription === undefined) return;
    void run(async () => {
      await deleteSubscription({ setupId: selectedSetupId, subscriptionId: subscription.subscriptionId }).unwrap();
      setSubscription(undefined);
      setRevealed(undefined);
      setStatus('Subscription stopped and its retained messages were discarded.');
    });
  };

  const publish = () => {
    if (selectedSetupId === undefined) return;
    void run(async () => {
      const accepted = await publishMessage({ setupId: selectedSetupId, channel: publishChannel, payload, contentType }).unwrap();
      setStatus(`Accepted by PostgreSQL at ${formatDisplayInstant(accepted.publishedAt)}.`);
      setPayload('');
    });
  };

  const reveal = (message: PubSubMessageMetadata) => {
    if (selectedSetupId === undefined || subscription === undefined || !canReveal) return;
    setRevealed(undefined);
    void run(async () => { setRevealed(await clients.pubSub.revealPayload(selectedSetupId, subscription.subscriptionId, message.messageId)); });
  };

  if (selectedSetupId === undefined) {
    return <Workspace><Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Pub/Sub unavailable</Title>}><Text type="secondary">Select a connected setup before using Pub/Sub.</Text></Empty></Card></Workspace>;
  }

  const columns = [
    { title: 'Message', dataIndex: 'messageId', key: 'messageId' },
    { title: 'Received', key: 'receivedAt', render: (_value: unknown, message: PubSubMessageMetadata) => formatDisplayInstant(message.receivedAt) },
    { title: 'Content type', key: 'contentType', render: (_value: unknown, message: PubSubMessageMetadata) => message.contentType ?? 'Not supplied' },
    { title: 'Bytes', dataIndex: 'payloadBytes', key: 'payloadBytes' },
    { title: 'Payload', key: 'payload', render: () => <Tag>Masked</Tag> },
    ...(canReveal ? [{
      title: 'Actions', key: 'actions',
      render: (_value: unknown, message: PubSubMessageMetadata) => <Button disabled={busy} onClick={() => reveal(message)} size="small" type="link">Reveal payload {message.messageId}</Button>,
    }] : []),
  ];

  return (
    <Workspace>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {problem !== undefined && <ProblemAlert problem={problem} />}
        {status !== '' && <Alert message={status} role="status" showIcon type="info" />}

        {subscription === undefined ? (
          <section aria-labelledby="subscribe-title" className="details-section">
            <Card title={<Title id="subscribe-title" level={2} style={{ margin: 0, fontSize: 18 }}>Subscribe</Title>}>
              <Paragraph>Subscriptions are non-durable and bounded. They end when this console session or setup closes.</Paragraph>
              <Form layout="vertical">
                <Form.Item htmlFor="pubsub-channel" label="Channel">
                  <Input id="pubsub-channel" maxLength={maximumChannelBytes} onChange={(event) => setChannel(event.target.value)} value={channel} />
                </Form.Item>
                <Text>{channelBytes} / {maximumChannelBytes} UTF-8 bytes</Text>
                <Form.Item htmlFor="pubsub-buffer" label="Buffer entries" style={{ marginTop: 16 }}>
                  <Input id="pubsub-buffer" max={500} min={1} onChange={(event) => setBufferLimit(event.target.value)} type="number" value={bufferLimit} />
                </Form.Item>
                <Button disabled={busy || channelBytes < 1 || channelBytes > maximumChannelBytes || !bufferValid} onClick={start} type="primary">Start subscription</Button>
              </Form>
            </Card>
          </section>
        ) : (
          <section aria-labelledby="subscription-title" className="details-section">
            <Card
              extra={<Button danger disabled={busy} onClick={stop}>Stop subscription</Button>}
              title={<Title id="subscription-title" level={2} style={{ margin: 0, fontSize: 18 }}>Subscription: {subscription.channel}</Title>}
            >
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Text>Non-durable · {connection} · bounded to {subscription.bufferLimit} messages</Text>
                {messages.length === 0
                  ? <Text>No retained message metadata.</Text>
                  : (
                    <div aria-label="Retained Pub/Sub messages" className="table-scroll" role="region" tabIndex={0}>
                      <Table columns={columns} dataSource={messages} pagination={false} rowKey="messageId" size="small" />
                    </div>
                  )}
              </Space>
            </Card>
          </section>
        )}

        {revealed !== undefined && (
          <section aria-labelledby="payload-title" className="details-section">
            <Card
              extra={<Button onClick={() => setRevealed(undefined)} type="primary">Hide payload</Button>}
              title={<Title id="payload-title" level={2} style={{ margin: 0, fontSize: 18 }}>Revealed payload</Title>}
            >
              <p><strong>Content type:</strong> {revealed.contentType ?? 'Not supplied'}</p>
              <pre className="value-content">{revealed.payload}</pre>
            </Card>
          </section>
        )}

        {canOperate && (
          <section aria-labelledby="publish-title" className="details-section">
            <Card title={<Title id="publish-title" level={2} style={{ margin: 0, fontSize: 18 }}>Publish</Title>}>
              <Paragraph>Success means accepted by PostgreSQL; it does not prove subscriber delivery. A content type is preserved for PeeGeeQ Cache subscribers.</Paragraph>
              <Form layout="vertical">
                <Form.Item htmlFor="publish-channel" label="Publish channel">
                  <Input id="publish-channel" maxLength={maximumChannelBytes} onChange={(event) => setPublishChannel(event.target.value)} value={publishChannel} />
                </Form.Item>
                <Text>{publishChannelBytes} / {maximumChannelBytes} channel UTF-8 bytes</Text>
                <Form.Item htmlFor="publish-content-type" label="Content type (optional)" style={{ marginTop: 16 }}>
                  <Input id="publish-content-type" maxLength={255} onChange={(event) => setContentType(event.target.value)} placeholder="application/json" value={contentType} />
                </Form.Item>
                <Form.Item htmlFor="publish-payload" label="Payload">
                  <Input.TextArea id="publish-payload" onChange={(event) => setPayload(event.target.value)} rows={4} value={payload} />
                </Form.Item>
                <Text>{payloadBytes} raw · {wirePayloadBytes} / {maximumPayloadBytes} wire bytes</Text>
                <div style={{ marginTop: 16 }}>
                  <Button disabled={busy || publishChannelBytes < 1 || publishChannelBytes > maximumChannelBytes || wirePayloadBytes > maximumPayloadBytes} onClick={publish} type="primary">Publish</Button>
                </div>
              </Form>
            </Card>
          </section>
        )}
      </Space>
    </Workspace>
  );
}

function Workspace({ children }: { readonly children: ReactNode }) {
  return (
    <section aria-labelledby="pubsub-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary">Process-local live messaging</Text>
        <Title id="pubsub-title" level={1} style={{ marginTop: 4 }}>Pub/Sub</Title>
      </div>
      {children}
    </section>
  );
}

function ProblemAlert({ problem }: { readonly problem: ManagementQueryError }) {
  return (
    <Alert
      description={<>{problem.message}{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</>}
      message={problem.code}
      role="alert"
      showIcon
      type="error"
    />
  );
}

function asQueryError(failure: unknown): ManagementQueryError {
  if (isManagementQueryError(failure)) return failure;
  if (failure instanceof ManagementClientError) return toQueryError(failure);
  return { status: 0, code: 'CONNECTION_FAILED', message: 'Pub/Sub operation failed' };
}

function utf8Bytes(value: string): number { return new TextEncoder().encode(value).byteLength; }

function encodedPayloadBytes(payload: string, contentType: string): number {
  const payloadBytes = utf8Bytes(payload);
  const normalizedType = contentType.trim();
  if (normalizedType === '' && !payload.startsWith('__PGQ_CACHE_TYPED_V1__:')) return payloadBytes;
  const binaryBytes = 1 + 4 + payloadBytes + utf8Bytes(normalizedType);
  return '__PGQ_CACHE_TYPED_V1__:'.length + Math.floor((binaryBytes * 4 + 2) / 3);
}
