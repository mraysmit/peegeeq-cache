import { useEffect, useState, type ReactNode } from 'react';

import type { PubSubClientPort } from '../../api/pubsub-client';
import type { PubSubMessageMetadata, RevealedPubSubPayload, SubscriptionSummary } from '../../api/pubsub-schemas';
import { FetchSseTransport, type LiveStream, type PubSubStreamPort } from '../../api/live-transport';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';
import { effectiveAutoHideMillis } from '../../state/preferences';

const defaultStream = new FetchSseTransport();

export function PubSubPage({ canOperate, canReveal, client, maximumChannelBytes, maximumPayloadBytes, selectedSetupId, stream = defaultStream }: {
  readonly canOperate: boolean; readonly canReveal: boolean; readonly client: PubSubClientPort;
  readonly maximumChannelBytes: number; readonly maximumPayloadBytes: number; readonly selectedSetupId?: string; readonly stream?: PubSubStreamPort;
}) {
  const [channel, setChannel] = useState(''); const [bufferLimit, setBufferLimit] = useState(20);
  const [subscription, setSubscription] = useState<SubscriptionSummary>(); const [connection, setConnection] = useState('STOPPED');
  const [messages, setMessages] = useState<PubSubMessageMetadata[]>([]); const [revealed, setRevealed] = useState<RevealedPubSubPayload>();
  const [publishChannel, setPublishChannel] = useState(''); const [payload, setPayload] = useState(''); const [contentType, setContentType] = useState('');
  const [status, setStatus] = useState(''); const [problem, setProblem] = useState<ManagementClientError>(); const [busy, setBusy] = useState(false);
  const channelBytes = utf8Bytes(channel); const publishChannelBytes = utf8Bytes(publishChannel); const payloadBytes = utf8Bytes(payload); const wirePayloadBytes = encodedPayloadBytes(payload, contentType);

  useEffect(() => { if (subscription === undefined) return undefined; const live: LiveStream = stream.connect(subscription.streamPath, { onMessage: (message) => setMessages((current) => [message, ...current.filter((item) => item.messageId !== message.messageId)].slice(0, subscription.bufferLimit)), onState: setConnection, onReset: (reason) => { setMessages([]); setStatus(reason); }, onError: (message) => setStatus(message) }); return () => live.stop(); }, [stream, subscription]);
  useEffect(() => { if (revealed === undefined) return undefined; const timer = window.setTimeout(() => setRevealed(undefined), effectiveAutoHideMillis(revealed.autoHideAfterMillis)); return () => window.clearTimeout(timer); }, [revealed]);
  useEffect(() => { const hide = () => { if (document.hidden) setRevealed(undefined); }; document.addEventListener('visibilitychange', hide); return () => document.removeEventListener('visibilitychange', hide); }, []);

  const start = async () => { if (selectedSetupId === undefined) return; setBusy(true); setProblem(undefined); try { setSubscription(await client.createSubscription(selectedSetupId, channel, bufferLimit)); setMessages([]); setStatus('Non-durable console subscription started. Messages are retained only in a bounded process-local buffer.'); } catch (failure) { setProblem(asError(failure)); } finally { setBusy(false); } };
  const stop = async () => { if (selectedSetupId === undefined || subscription === undefined) return; setBusy(true); try { await client.deleteSubscription(selectedSetupId, subscription.subscriptionId); setSubscription(undefined); setMessages([]); setRevealed(undefined); setConnection('STOPPED'); setStatus('Subscription stopped and its retained messages were discarded.'); } catch (failure) { setProblem(asError(failure)); } finally { setBusy(false); } };
  const publish = async () => { if (selectedSetupId === undefined) return; setBusy(true); setProblem(undefined); try { const accepted = await client.publish(selectedSetupId, publishChannel, payload, contentType); setStatus(`Accepted by PostgreSQL at ${formatDisplayInstant(accepted.publishedAt)}.`); setPayload(''); } catch (failure) { setProblem(asError(failure)); } finally { setBusy(false); } };
  const reveal = async (message: PubSubMessageMetadata) => { if (selectedSetupId === undefined || subscription === undefined) return; setBusy(true); setRevealed(undefined); try { setRevealed(await client.revealPayload(selectedSetupId, subscription.subscriptionId, message.messageId)); } catch (failure) { setProblem(asError(failure)); } finally { setBusy(false); } };

  if (selectedSetupId === undefined) return <Workspace><p>Select a connected setup before using Pub/Sub.</p></Workspace>;
  return <Workspace>
    {problem !== undefined && <div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p></div>}
    {status !== '' && <p role="status">{status}</p>}
    {subscription === undefined ? <section className="details-section" aria-labelledby="subscribe-title"><h2 id="subscribe-title">Subscribe</h2><p>Subscriptions are non-durable and bounded. They end when this console session or setup closes.</p><label className="field" htmlFor="pubsub-channel">Channel<input id="pubsub-channel" maxLength={maximumChannelBytes} onChange={(event) => setChannel(event.target.value)} value={channel} /></label><p>{channelBytes} / {maximumChannelBytes} UTF-8 bytes</p><label className="field" htmlFor="pubsub-buffer">Buffer entries<input id="pubsub-buffer" max={500} min={1} onChange={(event) => setBufferLimit(Number(event.target.value))} type="number" value={bufferLimit} /></label><button className="button" disabled={busy || channelBytes < 1 || channelBytes > maximumChannelBytes || bufferLimit < 1 || bufferLimit > 500} onClick={() => void start()} type="button">Start subscription</button></section> : <section className="details-section" aria-labelledby="subscription-title"><h2 id="subscription-title">Subscription: {subscription.channel}</h2><p>Non-durable · {connection} · bounded to {subscription.bufferLimit} messages</p><button className="button button--danger" disabled={busy} onClick={() => void stop()} type="button">Stop subscription</button><div aria-label="Retained Pub/Sub messages" className="table-scroll" role="region" tabIndex={0}><table className="data-table"><thead><tr><th>Message</th><th>Received</th><th>Content type</th><th>Bytes</th><th>Payload</th>{canReveal && <th>Actions</th>}</tr></thead><tbody>{messages.map((message) => <tr key={message.messageId}><td>{message.messageId}</td><td>{formatDisplayInstant(message.receivedAt)}</td><td>{message.contentType ?? 'Not supplied'}</td><td>{message.payloadBytes}</td><td>Masked</td>{canReveal && <td><button className="button button--quiet" disabled={busy} onClick={() => void reveal(message)} type="button">Reveal payload {message.messageId}</button></td>}</tr>)}</tbody></table></div>{messages.length === 0 && <p>No retained message metadata.</p>}</section>}
    {revealed !== undefined && <section className="details-section" aria-labelledby="payload-title"><h2 id="payload-title">Revealed payload</h2><p><strong>Content type:</strong> {revealed.contentType ?? 'Not supplied'}</p><pre className="value-content">{revealed.payload}</pre><button className="button" onClick={() => setRevealed(undefined)} type="button">Hide payload</button></section>}
    {canOperate && <section className="details-section" aria-labelledby="publish-title"><h2 id="publish-title">Publish</h2><p>Success means accepted by PostgreSQL; it does not prove subscriber delivery. A content type is preserved for PeeGeeQ Cache subscribers.</p><label className="field" htmlFor="publish-channel">Publish channel<input id="publish-channel" maxLength={maximumChannelBytes} onChange={(event) => setPublishChannel(event.target.value)} value={publishChannel} /></label><p>{publishChannelBytes} / {maximumChannelBytes} channel UTF-8 bytes</p><label className="field" htmlFor="publish-content-type">Content type (optional)<input id="publish-content-type" maxLength={255} onChange={(event) => setContentType(event.target.value)} placeholder="application/json" value={contentType} /></label><label className="field field--wide" htmlFor="publish-payload">Payload<textarea id="publish-payload" onChange={(event) => setPayload(event.target.value)} value={payload} /></label><p>{payloadBytes} raw · {wirePayloadBytes} / {maximumPayloadBytes} wire bytes</p><button className="button" disabled={busy || publishChannelBytes < 1 || publishChannelBytes > maximumChannelBytes || wirePayloadBytes > maximumPayloadBytes} onClick={() => void publish()} type="button">Publish</button></section>}
  </Workspace>;
}

function Workspace({ children }: { readonly children: ReactNode }) { return <section className="workspace" aria-labelledby="pubsub-title"><p className="workspace__context">Process-local live messaging</p><h1 id="pubsub-title">Pub/Sub</h1>{children}</section>; }
function asError(value: unknown) { return value instanceof ManagementClientError ? value : new ManagementClientError(0, 'CONNECTION_FAILED', 'Pub/Sub operation failed'); }
function utf8Bytes(value: string) { return new TextEncoder().encode(value).byteLength; }
function encodedPayloadBytes(payload: string, contentType: string) {
  const payloadBytes = utf8Bytes(payload);
  const normalizedType = contentType.trim();
  if (normalizedType === '' && !payload.startsWith('__PGQ_CACHE_TYPED_V1__:')) return payloadBytes;
  const binaryBytes = 1 + 4 + payloadBytes + utf8Bytes(normalizedType);
  return '__PGQ_CACHE_TYPED_V1__:'.length + Math.floor((binaryBytes * 4 + 2) / 3);
}
