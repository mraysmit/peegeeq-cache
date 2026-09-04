import { Alert, Button, Card, Empty, Form, Input, Modal, Space, Table, Typography, type InputRef } from 'antd';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import type { LockState, RevealedLockOwner } from '../../api/resource-schemas';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';
import { effectiveAutoHideMillis } from '../../state/preferences';
import { useManagementClients } from '../../store';
import { isManagementQueryError, toQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { useForceReleaseLockMutation, useGetLocksQuery, useLazyGetLockQuery } from '../../store/api/resourcesApi';

const { Title, Text } = Typography;

interface LocksPageProps {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly selectedSetupId?: string;
}

/**
 * Locks. The active-lease list is an RTK Query read; the management dialog always re-reads the
 * lock (lazy query, cache bypassed) so the version shown — and the one sent as `If-Match` on a
 * forced release — is the one PostgreSQL holds at that moment. The owner token is sensitive: it
 * is fetched through the no-store client and lives only in this component's state (design §8.2).
 */
export function LocksPage({ canOperate, canReveal, selectedSetupId }: LocksPageProps) {
  const canManage = canOperate || canReveal;
  const clients = useManagementClients();
  const locks = useGetLocksQuery({ setupId: selectedSetupId ?? '', query: { leaseState: 'ACTIVE', limit: 50 } }, { skip: selectedSetupId === undefined });
  const [loadLock] = useLazyGetLockQuery();
  const [forceRelease] = useForceReleaseLockMutation();
  const [active, setActive] = useState<LockState>();
  const [revealed, setRevealed] = useState<RevealedLockOwner>();
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [status, setStatus] = useState('');
  const [mutationProblem, setMutationProblem] = useState<ManagementQueryError>();
  const [busy, setBusy] = useState(false);
  const confirmationInput = useRef<InputRef>(null);

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

  const page = locks.data;
  const problem = mutationProblem ?? (isManagementQueryError(locks.error) ? locks.error : undefined);

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setMutationProblem(undefined);
    try {
      await operation();
    } catch (failure: unknown) {
      setMutationProblem(asQueryError(failure));
    } finally {
      setBusy(false);
    }
  };

  const freshLock = (item: LockState) => loadLock({ setupId: selectedSetupId ?? '', encodedNamespace: item.encodedNamespace, encodedKey: item.encodedKey }, false).unwrap();

  const manage = (item: LockState) => {
    if (selectedSetupId === undefined) return;
    setRevealed(undefined);
    setStatus('');
    void run(async () => { setActive(await freshLock(item)); });
  };

  const reveal = () => {
    if (selectedSetupId === undefined || active === undefined || !canReveal) return;
    void run(async () => { setRevealed(await clients.resource.revealLockOwner(selectedSetupId, active.encodedNamespace, active.encodedKey, reason || undefined)); });
  };

  const prepareRelease = () => {
    if (selectedSetupId === undefined || active === undefined) return;
    void run(async () => {
      setActive(await freshLock(active));
      setConfirmation('');
      setConfirming(true);
    });
  };

  const release = () => {
    if (selectedSetupId === undefined || active === undefined || confirmation !== active.key) return;
    void run(async () => {
      await forceRelease({ setupId: selectedSetupId, encodedNamespace: active.encodedNamespace, encodedKey: active.encodedKey, version: active.version, confirmationKey: confirmation, reason: reason || undefined }).unwrap();
      setStatus('Lock released after exact-version confirmation.');
      setConfirming(false);
      setActive(undefined);
      setRevealed(undefined);
    });
  };

  const closeManage = () => {
    setActive(undefined);
    setRevealed(undefined);
  };

  if (selectedSetupId === undefined) {
    return <Workspace><Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Lock data unavailable</Title>}><Text type="secondary">Select a connected setup before inspecting locks.</Text></Empty></Card></Workspace>;
  }

  const columns = [
    { title: 'Namespace', dataIndex: 'namespace', key: 'namespace' },
    { title: 'Key', dataIndex: 'key', key: 'key' },
    { title: 'Fencing token', dataIndex: 'fencingToken', key: 'fencingToken' },
    { title: 'Version', dataIndex: 'version', key: 'version' },
    { title: 'Lease expires', key: 'leaseExpiresAt', render: (_value: unknown, item: LockState) => formatDisplayInstant(item.leaseExpiresAt) },
    { title: 'Remaining', key: 'remaining', render: (_value: unknown, item: LockState) => `${item.leaseRemainingMillis} ms` },
    { title: 'Owner', key: 'owner', render: () => 'Masked' },
    ...(canManage ? [{
      title: 'Actions', key: 'actions',
      render: (_value: unknown, item: LockState) => <Button disabled={busy} onClick={() => manage(item)} size="small" type="link">Manage {item.key}</Button>,
    }] : []),
  ];

  return (
    <Workspace>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {problem !== undefined && <ProblemAlert problem={problem} />}
        {status !== '' && <Alert message={status} role="status" showIcon type="success" />}
        {page === undefined && locks.isLoading && <Text aria-busy="true">Loading locks…</Text>}
        {page !== undefined && page.items.length === 0 && <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>No active locks</Title>} /></Card>}
        {page !== undefined && page.items.length > 0 && (
          <div aria-label="Active locks results" className="table-scroll" role="region" tabIndex={0}>
            <Table columns={columns} dataSource={page.items} loading={locks.isFetching} pagination={false} rowKey={(item) => `${item.encodedNamespace}:${item.encodedKey}`} size="small" />
          </div>
        )}
      </Space>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={closeManage}
        open={active !== undefined && !confirming}
        title={<Title id="lock-title" level={2} style={{ margin: 0, fontSize: 20 }}>Manage {active?.key ?? ''}</Title>}
      >
        {active !== undefined && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Text>Version {active.version} · fencing token {active.fencingToken}</Text>
            <Form layout="vertical">
              <Form.Item htmlFor="lock-reason" label="Reason (optional)"><Input id="lock-reason" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} value={reason} /></Form.Item>
            </Form>
            <section aria-labelledby="lock-owner-title" className="details-section">
              <Title id="lock-owner-title" level={3} style={{ fontSize: 16 }}>Owner</Title>
              {revealed === undefined ? <p><Text>Masked</Text></p> : <pre className="value-content">{revealed.ownerToken}</pre>}
              {canReveal && (revealed === undefined
                ? <Button disabled={busy} onClick={reveal} type="primary">Reveal owner</Button>
                : <Button onClick={() => setRevealed(undefined)}>Hide owner</Button>)}
            </section>
            <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
              {canOperate && <Button danger disabled={busy} onClick={prepareRelease}>Force release</Button>}
              <Button onClick={closeManage}>Close</Button>
            </Space>
          </Space>
        )}
      </Modal>

      <Modal
        afterOpenChange={(open) => { if (open) confirmationInput.current?.focus(); }}
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setConfirming(false)}
        open={confirming && active !== undefined}
        title={<Title id="release-title" level={2} style={{ margin: 0, fontSize: 20 }}>Release current lock version?</Title>}
      >
        {active !== undefined && (
          <Form layout="vertical">
            <Text>The lock was reloaded immediately before this confirmation. A renewal or reacquisition will cause a conflict.</Text>
            <Form.Item htmlFor="lock-confirmation" label="Confirm lock key" style={{ marginTop: 16 }}><Input id="lock-confirmation" onChange={(event) => setConfirmation(event.target.value)} ref={confirmationInput} value={confirmation} /></Form.Item>
            <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
              <Button onClick={() => setConfirming(false)}>Cancel</Button>
              <Button danger disabled={busy || confirmation !== active.key} onClick={release} type="primary">Release current version</Button>
            </Space>
          </Form>
        )}
      </Modal>
    </Workspace>
  );
}

function Workspace({ children }: { readonly children: ReactNode }) {
  return (
    <section aria-labelledby="locks-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary">Active database leases</Text>
        <Title id="locks-title" level={1} style={{ marginTop: 4 }}>Locks</Title>
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
  return { status: 0, code: 'CONNECTION_FAILED', message: 'Lock operation failed' };
}
