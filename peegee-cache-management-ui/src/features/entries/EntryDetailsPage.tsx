import { Alert, Button, Card, Descriptions, Empty, Form, Input, Modal, Space, Typography } from 'antd';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { EntrySetBody } from '../../api/entry-administration-schemas';
import type { RevealedEntryValue } from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { ValueSelect } from '../../components/common/ValueSelect';
import { formatDisplayInstant } from '../../presentation/display-time';
import { effectiveAutoHideMillis } from '../../state/preferences';
import { useManagementClients } from '../../store';
import { isManagementQueryError, toQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { useDeleteEntryMutation, useExpireEntryMutation, usePersistEntryMutation, useSetEntryMutation, useTouchEntryMutation } from '../../store/api/entriesApi';
import { useGetEntryQuery } from '../../store/api/inspectionApi';
import { EntryValueFormatter } from './EntryValueFormatter';
import { ENTRY_SET_MODE_OPTIONS, ENTRY_TTL_MODE_OPTIONS, EntryInputError, cacheValueFor, positiveInteger } from './entry-filters';
import { copyableValue } from './value-formatting';

const { Title, Text } = Typography;

interface EntryDetailsPageProps {
  readonly canOperate?: boolean;
  readonly canReveal: boolean;
  readonly encodedKey: string;
  readonly encodedNamespace: string;
  readonly selectedSetupId?: string;
}

/**
 * Entry details. Metadata is an RTK Query read; the revealed value is fetched through the
 * no-store client and held only in this component's state with auto-hide (design §8.2).
 * Mutations go through `entriesApi`, whose invalidations refresh the metadata from the server.
 */
export function EntryDetailsPage({ canOperate = false, canReveal, encodedKey, encodedNamespace, selectedSetupId }: EntryDetailsPageProps) {
  const clients = useManagementClients();
  const metadataQuery = useGetEntryQuery(
    { setupId: selectedSetupId ?? '', encodedNamespace, encodedKey, includeExpired: true },
    { skip: selectedSetupId === undefined },
  );
  const [setEntry] = useSetEntryMutation();
  const [expireEntry] = useExpireEntryMutation();
  const [persistEntry] = usePersistEntryMutation();
  const [touchEntry] = useTouchEntryMutation();
  const [deleteEntry] = useDeleteEntryMutation();

  const [problem, setProblem] = useState<ManagementQueryError>();
  const [revealed, setRevealed] = useState<RevealedEntryValue>();
  const [reason, setReason] = useState('');
  const [revealing, setRevealing] = useState(false);
  const [copyStatus, setCopyStatus] = useState('');
  const [operationStatus, setOperationStatus] = useState('');
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState('');
  const [setMode, setSetMode] = useState<EntrySetBody['setMode']>('ONLY_IF_VERSION_MATCHES');
  const [ttlMode, setTtlMode] = useState<EntrySetBody['ttlMode']>('PRESERVE_EXISTING');
  const [editTtlMillis, setEditTtlMillis] = useState('');
  const [ttlMillis, setTtlMillis] = useState('');
  const [refreshTtlMillis, setRefreshTtlMillis] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [deleteConfirmation, setDeleteConfirmation] = useState('');
  const [mutating, setMutating] = useState(false);
  const generation = useRef(0);
  const sensitiveScope = `${selectedSetupId ?? ''}:${encodedNamespace}:${encodedKey}:${canReveal}`;

  // A route or permission change invalidates any revealed value immediately.
  useEffect(() => {
    generation.current += 1;
    return () => {
      setRevealed(undefined);
      setReason('');
      setCopyStatus('');
      setOperationStatus('');
      setEditing(false);
      setDeleting(false);
    };
  }, [sensitiveScope]);

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

  const metadata = metadataQuery.data;
  const routeMismatch = metadata !== undefined && (metadata.encodedNamespace !== encodedNamespace || metadata.encodedKey !== encodedKey);
  const loadProblem = isManagementQueryError(metadataQuery.error)
    ? metadataQuery.error
    : routeMismatch ? { status: 502, code: 'RESPONSE_CONTRACT_INVALID', message: 'The server returned entry metadata for a different route' } : undefined;

  const reveal = async () => {
    if (selectedSetupId === undefined || metadata === undefined || !canReveal) return;
    const acceptedGeneration = generation.current;
    setRevealing(true);
    setProblem(undefined);
    try {
      const loaded = await clients.inspection.revealEntryValue(selectedSetupId, encodedNamespace, encodedKey, reason || undefined);
      if (acceptedGeneration !== generation.current) return;
      if (loaded.key !== metadata.key) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned a revealed value for a different entry');
      setRevealed(loaded);
      setCopyStatus('');
    } catch (failure: unknown) {
      if (acceptedGeneration === generation.current) setProblem(asQueryError(failure));
    } finally {
      if (acceptedGeneration === generation.current) setRevealing(false);
    }
  };

  const copy = async () => {
    if (revealed === undefined) return;
    try {
      await globalThis.navigator.clipboard.writeText(copyableValue(revealed.value));
      setCopyStatus('Copied');
    } catch {
      setCopyStatus('Copy failed');
    }
  };

  const runMutation = async (operation: () => Promise<string>) => {
    setMutating(true);
    setProblem(undefined);
    setOperationStatus('');
    try {
      setOperationStatus(await operation());
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
    } finally {
      setMutating(false);
    }
  };

  const saveEntry = () => {
    if (selectedSetupId === undefined || metadata === undefined) return;
    void runMutation(async () => {
      const body: EntrySetBody = {
        value: cacheValueFor(metadata.valueType, editValue),
        ttlMode,
        ttlMillis: ttlMode === 'REPLACE' ? positiveInteger(editTtlMillis, 'Replacement TTL') : null,
        setMode,
      };
      const result = await setEntry({
        setupId: selectedSetupId, encodedNamespace, encodedKey, body,
        observedVersion: setMode === 'ONLY_IF_VERSION_MATCHES' ? metadata.version : undefined,
      }).unwrap();
      setEditing(false);
      setEditValue('');
      return `Entry ${result.created ? 'created' : 'updated'} at ${formatDisplayInstant(result.updatedAt)} · version ${result.version}`;
    });
  };

  const confirmDelete = () => {
    if (selectedSetupId === undefined || metadata === undefined) return;
    void runMutation(async () => {
      await deleteEntry({ setupId: selectedSetupId, encodedNamespace, encodedKey, version: metadata.version }).unwrap();
      setDeleting(false);
      setDeleteConfirmation('');
      return 'Entry deleted. Return to the Key Browser to continue.';
    });
  };

  if (selectedSetupId === undefined) return <Workspace title="Entry details"><EmptyState>Select a connected setup before inspecting an entry.</EmptyState></Workspace>;
  if (metadata === undefined && loadProblem !== undefined) return <Workspace title="Entry details"><ProblemAlert problem={loadProblem} /></Workspace>;
  if (metadata === undefined || routeMismatch) return <Workspace title="Entry details"><Text aria-busy="true">Loading entry metadata…</Text></Workspace>;

  return (
    <Workspace title={metadata.key}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {problem !== undefined && <ProblemAlert problem={problem} />}
        <Text className="scope-label" type="secondary">Namespace: <strong>{metadata.namespace}</strong></Text>
        <Card>
          <Descriptions aria-label="Entry metadata" column={3} size="small">
            <Descriptions.Item label="Type">{metadata.valueType}</Descriptions.Item>
            <Descriptions.Item label="Size">{`${BigInt(metadata.sizeBytes).toLocaleString('en-US')} B`}</Descriptions.Item>
            <Descriptions.Item label="Version">{metadata.version}</Descriptions.Item>
            <Descriptions.Item label="Created">{formatDisplayInstant(metadata.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="Updated">{formatDisplayInstant(metadata.updatedAt)}</Descriptions.Item>
            <Descriptions.Item label="Last accessed">{formatOptionalInstant(metadata.lastAccessedAt)}</Descriptions.Item>
            <Descriptions.Item label="Expires">{formatOptionalInstant(metadata.ttl.expiresAt)}</Descriptions.Item>
            <Descriptions.Item label="Remaining TTL">{formatNullableDuration(metadata.ttl.ttlMillis)}</Descriptions.Item>
            <Descriptions.Item label="Status">{metadata.ttl.state}</Descriptions.Item>
          </Descriptions>
        </Card>
        {operationStatus !== '' && <Alert message={operationStatus} role="status" showIcon type="success" />}
        {canOperate && (
          <section aria-labelledby="entry-administration-title" className="details-section">
            <Card
              extra={<Button onClick={() => setEditing(!editing)} type={editing ? 'default' : 'primary'}>{editing ? 'Cancel edit' : 'Edit entry'}</Button>}
              title={(
                <>
                  <Title id="entry-administration-title" level={2} style={{ margin: 0, fontSize: 18 }}>Entry administration</Title>
                  <Text type="secondary">Success is shown only after a validated server response.</Text>
                </>
              )}
            >
              {editing && (
                <Form className="form-grid" layout="vertical" onFinish={saveEntry}>
                  <Form.Item htmlFor="entry-edit-type" label="Value type"><Input disabled id="entry-edit-type" value={metadata.valueType} /></Form.Item>
                  <Form.Item htmlFor="entry-set-mode" label="Set mode"><ValueSelect<EntrySetBody['setMode']> id="entry-set-mode" onChange={setSetMode} options={ENTRY_SET_MODE_OPTIONS} value={setMode} /></Form.Item>
                  <Form.Item htmlFor="entry-edit-value" label="Entry value"><Input.TextArea id="entry-edit-value" onChange={(event) => setEditValue(event.target.value)} required rows={4} value={editValue} /></Form.Item>
                  <Form.Item htmlFor="entry-ttl-mode" label="TTL behavior"><ValueSelect<EntrySetBody['ttlMode']> id="entry-ttl-mode" onChange={setTtlMode} options={ENTRY_TTL_MODE_OPTIONS} value={ttlMode} /></Form.Item>
                  <Form.Item htmlFor="entry-edit-ttl" label="Replacement TTL milliseconds"><Input disabled={ttlMode !== 'REPLACE'} id="entry-edit-ttl" min={1} onChange={(event) => setEditTtlMillis(event.target.value)} required={ttlMode === 'REPLACE'} type="number" value={editTtlMillis} /></Form.Item>
                  <Space className="modal__actions"><Button disabled={mutating} htmlType="submit" type="primary">Save entry</Button></Space>
                </Form>
              )}
              <Form className="form-grid" layout="vertical">
                <Form.Item htmlFor="entry-ttl-millis" label="TTL milliseconds"><Input id="entry-ttl-millis" min={1} onChange={(event) => setTtlMillis(event.target.value)} type="number" value={ttlMillis} /></Form.Item>
                <Space className="workspace__actions" wrap>
                  <Button disabled={mutating || ttlMillis === ''} onClick={() => void runMutation(async () => { await expireEntry({ setupId: selectedSetupId, encodedNamespace, encodedKey, version: metadata.version, ttlMillis: positiveInteger(ttlMillis, 'TTL') }).unwrap(); return 'Entry TTL updated from the committed server result.'; })} type="primary">Set TTL</Button>
                  <Button disabled={mutating} onClick={() => void runMutation(async () => { await persistEntry({ setupId: selectedSetupId, encodedNamespace, encodedKey, version: metadata.version }).unwrap(); return 'Entry is persistent.'; })}>Make persistent</Button>
                </Space>
                <Form.Item htmlFor="entry-refresh-ttl" label="Refresh TTL milliseconds (optional)"><Input id="entry-refresh-ttl" min={1} onChange={(event) => setRefreshTtlMillis(event.target.value)} type="number" value={refreshTtlMillis} /></Form.Item>
                <Space className="workspace__actions" wrap>
                  <Button disabled={mutating} onClick={() => void runMutation(async () => { await touchEntry({ setupId: selectedSetupId, encodedNamespace, encodedKey, version: metadata.version, refreshTtlMillis: refreshTtlMillis === '' ? null : positiveInteger(refreshTtlMillis, 'Refresh TTL') }).unwrap(); return 'Entry touched using authoritative metadata.'; })}>Touch entry</Button>
                  <Button danger disabled={mutating} onClick={() => setDeleting(true)}>Delete entry</Button>
                </Space>
              </Form>
            </Card>
          </section>
        )}
        <ValuePanel canReveal={canReveal} copy={copy} copyStatus={copyStatus} reason={reason} reveal={reveal} revealed={revealed} revealing={revealing} setReason={setReason} setRevealed={setRevealed} />
      </Space>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setDeleting(false)}
        open={deleting}
        title={<Title id="delete-entry-title" level={2} style={{ margin: 0, fontSize: 20 }}>Delete {metadata.key}?</Title>}
      >
        <Text>This exact version is deleted only if it has not changed.</Text>
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item htmlFor="delete-entry-confirmation" label="Confirm entry key"><Input id="delete-entry-confirmation" onChange={(event) => setDeleteConfirmation(event.target.value)} value={deleteConfirmation} /></Form.Item>
        </Form>
        <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
          <Button onClick={() => setDeleting(false)}>Cancel</Button>
          <Button danger disabled={deleteConfirmation !== metadata.key || mutating} onClick={confirmDelete} type="primary">Confirm delete</Button>
        </Space>
      </Modal>
    </Workspace>
  );
}

function ValuePanel({ canReveal, copy, copyStatus, reason, reveal, revealed, revealing, setReason, setRevealed }: {
  canReveal: boolean; copy: () => Promise<void>; copyStatus: string; reason: string; reveal: () => Promise<void>;
  revealed?: RevealedEntryValue; revealing: boolean; setReason: (value: string) => void; setRevealed: (value?: RevealedEntryValue) => void;
}) {
  return (
    <section aria-labelledby="value-title" className="value-panel">
      <Card
        extra={revealed !== undefined && (
          <Space className="workspace__actions">
            <Button onClick={() => void copy()}>Copy revealed value</Button>
            <Button onClick={() => setRevealed(undefined)} type="primary">Hide value</Button>
          </Space>
        )}
        title={(
          <>
            <Text className="workspace__context" type="secondary">Sensitive data</Text>
            <Title id="value-title" level={2} style={{ margin: 0, fontSize: 18 }}>Value</Title>
          </>
        )}
      >
        {revealed === undefined ? (
          <div className="masked-value">
            <Text strong>Value hidden</Text>
            <p><Text type="secondary">Values are excluded from ordinary metadata requests and remain hidden until explicitly revealed.</Text></p>
            {canReveal ? (
              <Form className="reveal-form" layout="inline" onFinish={() => void reveal()}>
                <Form.Item htmlFor="reveal-reason" label="Reveal reason (optional)"><Input id="reveal-reason" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} style={{ width: 260 }} value={reason} /></Form.Item>
                <Form.Item><Button disabled={revealing} htmlType="submit" type="primary">{revealing ? 'Revealing…' : 'Reveal value'}</Button></Form.Item>
              </Form>
            ) : <Text>Operator permission and sensitive-reveal capability are required.</Text>}
          </div>
        ) : (
          <>
            <Text className="scope-label" type="secondary">Revealed at {formatDisplayInstant(revealed.revealedAt)} · Automatically hidden after {formatDuration(effectiveAutoHideMillis(revealed.autoHideAfterMillis))}</Text>
            <EntryValueFormatter value={revealed.value} />
            {copyStatus !== '' && <Text aria-live="polite">{copyStatus}</Text>}
          </>
        )}
      </Card>
    </section>
  );
}

function Workspace({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  return (
    <section aria-labelledby="entry-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary"><Link to="/keys">Key Browser</Link></Text>
        <Title id="entry-title" level={1} style={{ marginTop: 4 }}>{title}</Title>
      </div>
      {children}
    </section>
  );
}

function EmptyState({ children }: { readonly children: ReactNode }) {
  return <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Entry unavailable</Title>}><Text type="secondary">{children}</Text></Empty></Card>;
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
  if (failure instanceof EntryInputError) return { status: 400, code: failure.code, message: failure.message };
  if (failure instanceof ManagementClientError) return toQueryError(failure);
  return { status: 0, code: 'CONNECTION_FAILED', message: 'Entry administration could not be completed' };
}

function formatDuration(milliseconds: number): string { return milliseconds < 1_000 ? `${milliseconds} ms` : `${milliseconds / 1_000} s`; }
function formatNullableDuration(milliseconds: number | null): string { return milliseconds === null ? 'Persistent' : formatDuration(milliseconds); }
function formatOptionalInstant(instant: string | null | undefined): string { return instant === null || instant === undefined ? 'Never' : formatDisplayInstant(instant); }
