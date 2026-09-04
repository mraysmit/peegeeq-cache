import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Empty, Form, Input, InputNumber, Modal, Space, Table, Typography } from 'antd';
import { useState, type ReactNode } from 'react';

import type { BulkDeletePreview } from '../../api/entry-administration-schemas';
import { encodeKey, encodeNamespace } from '../../api/identifier-codec';
import type { CounterQuery } from '../../api/resource-client';
import type { Counter } from '../../api/resource-schemas';
import { formatDisplayInstant } from '../../presentation/display-time';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import {
  useAdjustCounterMutation,
  useDeleteCounterMutation,
  useExecuteCounterBulkDeleteMutation,
  useExpireCounterMutation,
  useGetCountersQuery,
  usePersistCounterMutation,
  usePreviewCounterBulkDeleteMutation,
  useSetCounterMutation,
} from '../../store/api/resourcesApi';

const { Title, Text } = Typography;

interface CountersPageProps {
  readonly canBulkDelete?: boolean;
  readonly canOperate: boolean;
  readonly selectedSetupId?: string;
}

/**
 * Counters (reference layout: filter card, Table with row selection, Modal editor). The list is an
 * RTK Query read keyed by setup, trimmed filters, and cursor; every mutation returns the committed
 * counter (shown in the status line) and invalidates the list so the table reflects PostgreSQL.
 * Values are signed 64-bit decimal strings end to end: antd `InputNumber` runs in `stringMode`, so
 * no JavaScript number is ever formed from them.
 */
export function CountersPage({ canOperate, canBulkDelete = canOperate, selectedSetupId }: CountersPageProps) {
  const [namespace, setNamespace] = useState('');
  const [prefix, setPrefix] = useState('');
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [active, setActive] = useState<Counter>();
  const [creating, setCreating] = useState(false);
  const [createNamespace, setCreateNamespace] = useState('');
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [delta, setDelta] = useState('');
  const [ttl, setTtl] = useState('');
  const [deleteConfirmation, setDeleteConfirmation] = useState('');
  const [preview, setPreview] = useState<BulkDeletePreview>();
  const [confirmation, setConfirmation] = useState('');
  const [status, setStatus] = useState('');
  const [mutationProblem, setMutationProblem] = useState<ManagementQueryError>();
  const [busy, setBusy] = useState(false);

  const query: CounterQuery = { namespace: namespace.trim() || undefined, prefix: prefix.trim() || undefined, ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50, cursor };
  const counters = useGetCountersQuery({ setupId: selectedSetupId ?? '', query }, { skip: selectedSetupId === undefined });
  const [setCounter] = useSetCounterMutation();
  const [adjustCounter] = useAdjustCounterMutation();
  const [expireCounter] = useExpireCounterMutation();
  const [persistCounter] = usePersistCounterMutation();
  const [deleteCounter] = useDeleteCounterMutation();
  const [previewBulkDelete] = usePreviewCounterBulkDeleteMutation();
  const [executeBulkDelete] = useExecuteCounterBulkDeleteMutation();
  const page = counters.data;
  const loading = counters.isFetching;
  const problem = mutationProblem ?? (isManagementQueryError(counters.error) ? counters.error : undefined);

  const changeFilter = (next: { namespace?: string; prefix?: string }) => {
    if (next.namespace !== undefined) setNamespace(next.namespace);
    if (next.prefix !== undefined) setPrefix(next.prefix);
    setCursor(undefined);
    setHistory([]);
    setSelected(new Set());
  };

  const run = async (operation: () => Promise<void>) => {
    setBusy(true);
    setMutationProblem(undefined);
    setStatus('');
    try {
      await operation();
    } catch (failure: unknown) {
      setMutationProblem(asQueryError(failure));
    } finally {
      setBusy(false);
    }
  };

  const commit = (operation: () => Promise<Counter>, message: string) => run(async () => {
    const updated = await operation();
    setActive(updated);
    setCreating(false);
    setStatus(`${message}: ${formatSigned(updated.value)} · version ${updated.version}`);
  });

  const setExact = () => {
    if (selectedSetupId === undefined) return;
    const target = active ?? { encodedNamespace: encodeNamespace(createNamespace), encodedKey: encodeKey(key), version: undefined };
    void commit(
      () => setCounter({
        setupId: selectedSetupId, encodedNamespace: target.encodedNamespace, encodedKey: target.encodedKey, version: target.version,
        body: { value, ttlMode: ttl === '' ? 'PRESERVE_EXISTING' : 'REPLACE', ttlMillis: ttl === '' ? null : positive(ttl) },
      }).unwrap(),
      active === undefined ? 'Counter created' : 'Counter set',
    );
  };

  const adjust = () => {
    if (selectedSetupId === undefined) return;
    const createIfMissing = active === undefined;
    const target = active ?? { encodedNamespace: encodeNamespace(createNamespace), encodedKey: encodeKey(key), version: undefined };
    const ttlMode = createIfMissing ? (ttl === '' ? 'REMOVE' : 'REPLACE') : 'PRESERVE_EXISTING';
    void commit(
      () => adjustCounter({
        setupId: selectedSetupId, encodedNamespace: target.encodedNamespace, encodedKey: target.encodedKey, version: target.version,
        body: { delta, createIfMissing, ttlMode, ttlMillis: ttlMode === 'REPLACE' ? positive(ttl) : null },
      }).unwrap(),
      createIfMissing ? 'Counter created by adjustment' : 'Counter adjusted',
    );
  };

  const setCounterTtl = () => {
    if (selectedSetupId === undefined || active === undefined) return;
    void commit(() => expireCounter({ setupId: selectedSetupId, encodedNamespace: active.encodedNamespace, encodedKey: active.encodedKey, version: active.version, ttlMillis: positive(ttl) }).unwrap(), 'Counter TTL set');
  };

  const persist = () => {
    if (selectedSetupId === undefined || active === undefined) return;
    void commit(() => persistCounter({ setupId: selectedSetupId, encodedNamespace: active.encodedNamespace, encodedKey: active.encodedKey, version: active.version }).unwrap(), 'Counter persisted');
  };

  const deleteActive = () => {
    if (selectedSetupId === undefined || active === undefined || deleteConfirmation !== active.key) return;
    void run(async () => {
      await deleteCounter({ setupId: selectedSetupId, encodedNamespace: active.encodedNamespace, encodedKey: active.encodedKey, version: active.version }).unwrap();
      setStatus(`Counter ${active.key} deleted at observed version ${active.version}.`);
      closeEditor();
    });
  };

  const previewDeletion = () => {
    if (selectedSetupId === undefined || page === undefined) return;
    void run(async () => {
      const targets = page.items.filter((item) => selected.has(id(item))).map((item) => ({ namespace: item.namespace, key: item.key, version: item.version }));
      setPreview(await previewBulkDelete({ setupId: selectedSetupId, selection: { targets } }).unwrap());
      setConfirmation('');
    });
  };

  const executeDeletion = () => {
    if (selectedSetupId === undefined || preview === undefined || confirmation !== preview.confirmationPhrase) return;
    void run(async () => {
      const result = await executeBulkDelete({ setupId: selectedSetupId, confirmation: { previewToken: preview.previewToken, confirmationPhrase: confirmation } }).unwrap();
      setStatus(`Deleted ${result.deletedCount} of ${result.processedCount} counters.`);
      setPreview(undefined);
      setSelected(new Set());
    });
  };

  const openCreate = () => {
    setActive(undefined);
    setCreating(true);
    setCreateNamespace(namespace.trim());
    setKey('');
    setValue('');
    setDelta('');
    setTtl('');
  };

  const openManage = (item: Counter) => {
    setActive(item);
    setCreating(false);
    setValue(item.value);
    setDelta('');
    setTtl('');
    setDeleteConfirmation('');
  };

  const closeEditor = () => {
    setActive(undefined);
    setCreating(false);
    setDeleteConfirmation('');
  };

  const toggle = (item: Counter) => {
    const next = new Set(selected);
    if (next.has(id(item))) next.delete(id(item)); else next.add(id(item));
    setSelected(next);
  };

  if (selectedSetupId === undefined) {
    return <Workspace><EmptyState>Select a connected setup before inspecting counters.</EmptyState></Workspace>;
  }

  const columns = [
    ...(canBulkDelete ? [{
      title: 'Selection', key: 'selection',
      render: (_value: unknown, item: Counter) => <Checkbox aria-label={`Select ${item.namespace}/${item.key}`} checked={selected.has(id(item))} onChange={() => toggle(item)} />,
    }] : []),
    { title: 'Namespace', dataIndex: 'namespace', key: 'namespace' },
    { title: 'Key', dataIndex: 'key', key: 'key' },
    { title: 'Value', key: 'value', render: (_value: unknown, item: Counter) => formatSigned(item.value) },
    { title: 'Version', dataIndex: 'version', key: 'version' },
    { title: 'Updated', key: 'updatedAt', render: (_value: unknown, item: Counter) => formatDisplayInstant(item.updatedAt) },
    { title: 'TTL', key: 'ttl', render: (_value: unknown, item: Counter) => (item.ttl.ttlMillis === null ? 'Persistent' : `${item.ttl.ttlMillis} ms`) },
    ...(canOperate ? [{
      title: 'Actions', key: 'actions',
      render: (_value: unknown, item: Counter) => <Button onClick={() => openManage(item)} size="small" type="link">Manage {item.key}</Button>,
    }] : []),
  ];

  return (
    <Workspace actions={canOperate ? <Button icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate} type="primary">Create counter</Button> : undefined}>
      <Card className="filter-bar filter-bar--counters" size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline">
          <Form.Item htmlFor="counter-namespace" label="Namespace">
            <Input id="counter-namespace" maxLength={128} onChange={(event) => changeFilter({ namespace: event.target.value })} style={{ width: 220 }} value={namespace} />
          </Form.Item>
          <Form.Item htmlFor="counter-prefix" label="Key prefix">
            <Input id="counter-prefix" maxLength={1024} onChange={(event) => changeFilter({ prefix: event.target.value })} style={{ width: 220 }} value={prefix} />
          </Form.Item>
        </Form>
      </Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {problem !== undefined && <ProblemAlert problem={problem} />}
        {status !== '' && <Alert message={status} role="status" showIcon type="success" />}
        {canBulkDelete && (
          <Space className="workspace__actions" wrap>
            <Button danger disabled={selected.size === 0 || busy} onClick={previewDeletion}>Preview selected counter deletion</Button>
          </Space>
        )}
        {page === undefined && counters.isLoading && <Text aria-busy="true">Loading counters…</Text>}
        {page !== undefined && page.items.length === 0 && <EmptyState>No counters matched.</EmptyState>}
        {page !== undefined && page.items.length > 0 && (
          <div aria-label="Counters results" className="table-scroll" role="region" tabIndex={0}>
            <Table columns={columns} dataSource={page.items} loading={loading} pagination={false} rowKey={id} size="small" />
          </div>
        )}
        {page !== undefined && (
          <nav aria-label="Counter pages" className="pagination">
            <Space>
              <Button disabled={history.length === 0 || loading} onClick={() => { const prior = history.at(-1); if (prior === undefined) return; setHistory((current) => current.slice(0, -1)); setCursor(prior ?? undefined); setSelected(new Set()); }}>Previous page</Button>
              <Text>Page {history.length + 1}</Text>
              <Button disabled={!page.hasMore || loading} onClick={() => { if (page.nextCursor === null) return; setHistory((current) => [...current, cursor ?? null]); setCursor(page.nextCursor); setSelected(new Set()); }}>Next page</Button>
            </Space>
          </nav>
        )}
      </Space>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={closeEditor}
        open={active !== undefined || creating}
        title={<Title id="counter-editor-title" level={2} style={{ margin: 0, fontSize: 20 }}>{active === undefined ? 'Create counter' : `Manage ${active.key}`}</Title>}
      >
        <Form className="form-grid" layout="vertical">
          {active === undefined && (
            <>
              <Form.Item htmlFor="counter-create-namespace" label="Namespace"><Input id="counter-create-namespace" maxLength={128} onChange={(event) => setCreateNamespace(event.target.value)} required value={createNamespace} /></Form.Item>
              <Form.Item htmlFor="counter-create-key" label="Key"><Input id="counter-create-key" maxLength={1024} onChange={(event) => setKey(event.target.value)} required value={key} /></Form.Item>
            </>
          )}
          <Form.Item htmlFor="counter-exact-value" label="Exact decimal value">
            <InputNumber<string> controls={false} id="counter-exact-value" onChange={(next) => setValue(next ?? '')} stringMode style={{ width: '100%' }} value={value === '' ? null : value} />
          </Form.Item>
          {active === undefined && (
            <Form.Item htmlFor="counter-create-ttl" label="TTL milliseconds (blank for persistent)"><Input id="counter-create-ttl" min={1} onChange={(event) => setTtl(event.target.value)} type="number" value={ttl} /></Form.Item>
          )}
          <Space className="workspace__actions" wrap><Button disabled={busy || value === ''} onClick={setExact} type="primary">Set exact value</Button></Space>
          <Form.Item htmlFor="counter-adjustment" label="Signed adjustment" style={{ marginTop: 16 }}>
            <InputNumber<string> controls={false} id="counter-adjustment" onChange={(next) => setDelta(next ?? '')} stringMode style={{ width: '100%' }} value={delta === '' ? null : delta} />
          </Form.Item>
          <Space className="workspace__actions" wrap><Button disabled={busy || delta === '' || delta === '0'} onClick={adjust}>{active === undefined ? 'Create by adjustment' : 'Apply adjustment'}</Button></Space>
          {active !== undefined && (
            <>
              <Form.Item htmlFor="counter-ttl" label="TTL milliseconds" style={{ marginTop: 16 }}><Input id="counter-ttl" min={1} onChange={(event) => setTtl(event.target.value)} type="number" value={ttl} /></Form.Item>
              <Space className="workspace__actions" wrap>
                <Button disabled={busy || ttl === ''} onClick={setCounterTtl}>Set counter TTL</Button>
                <Button disabled={busy} onClick={persist}>Make counter persistent</Button>
              </Space>
              <Form.Item htmlFor="counter-delete-confirm" label="Confirm counter key" style={{ marginTop: 16 }}><Input id="counter-delete-confirm" onChange={(event) => setDeleteConfirmation(event.target.value)} value={deleteConfirmation} /></Form.Item>
              <Space className="workspace__actions" wrap><Button danger disabled={busy || deleteConfirmation !== active.key} onClick={deleteActive}>Delete current version</Button></Space>
            </>
          )}
          <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%', marginTop: 16 }}><Button onClick={closeEditor}>Close</Button></Space>
        </Form>
      </Modal>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setPreview(undefined)}
        open={preview !== undefined}
        title={<Title id="counter-bulk-title" level={2} style={{ margin: 0, fontSize: 20 }}>Confirm counter deletion</Title>}
      >
        {preview !== undefined && (
          <Form layout="vertical">
            <Text>{preview.resolvedCount} counters · required phrase <strong>{preview.confirmationPhrase}</strong></Text>
            <Form.Item htmlFor="counter-bulk-confirm" label="Type confirmation phrase" style={{ marginTop: 16 }}><Input autoComplete="off" id="counter-bulk-confirm" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} /></Form.Item>
            <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
              <Button onClick={() => setPreview(undefined)}>Cancel</Button>
              <Button danger disabled={busy || confirmation !== preview.confirmationPhrase} onClick={executeDeletion} type="primary">Delete previewed counters</Button>
            </Space>
          </Form>
        )}
      </Modal>
    </Workspace>
  );
}

function Workspace({ actions, children }: { readonly actions?: ReactNode; readonly children: ReactNode }) {
  return (
    <section aria-labelledby="counters-title" className="workspace">
      <div className="workspace__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Text className="workspace__context" type="secondary">Exact signed 64-bit administration</Text>
          <Title id="counters-title" level={1} style={{ marginTop: 4 }}>Counters</Title>
        </div>
        {actions !== undefined && <Space className="workspace__actions">{actions}</Space>}
      </div>
      {children}
    </section>
  );
}

function EmptyState({ children }: { readonly children: ReactNode }) {
  return <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Counter data unavailable</Title>}><Text type="secondary">{children}</Text></Empty></Card>;
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
  if (failure instanceof CounterInputError) return { status: 400, code: failure.code, message: failure.message };
  return { status: 0, code: 'CONNECTION_FAILED', message: 'Counter operation failed' };
}

class CounterInputError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}

function id(item: Counter): string { return `${item.encodedNamespace}:${item.encodedKey}`; }
function positive(value: string): number {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number < 1) throw new CounterInputError('VALIDATION_FAILED', 'TTL must be a positive integer');
  return number;
}
function formatSigned(value: string): string { return BigInt(value).toLocaleString('en-US'); }
