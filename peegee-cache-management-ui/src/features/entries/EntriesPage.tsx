import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Empty, Form, Input, Modal, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { BulkDeletePreview } from '../../api/entry-administration-schemas';
import { encodeKey, encodeNamespace } from '../../api/identifier-codec';
import type { EntryQuery } from '../../api/inspection-client';
import type { EntryPage } from '../../api/inspection-schemas';
import { SetupScopeBar } from '../../components/common/SetupScopeBar';
import { ValueSelect } from '../../components/common/ValueSelect';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { formatDecimal, humanize } from '../../presentation/display-format';
import { formatDisplayInstant } from '../../presentation/display-time';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { useExecuteEntryBulkDeleteMutation, usePreviewEntryBulkDeleteMutation, useSetEntryMutation } from '../../store/api/entriesApi';
import { useGetEntriesQuery } from '../../store/api/inspectionApi';
import {
  ENTRY_TTL_STATE_OPTIONS,
  ENTRY_VALUE_TYPE_FILTER_OPTIONS,
  ENTRY_VALUE_TYPE_OPTIONS,
  EntryInputError,
  cacheValueFor,
  formatTtl,
  positiveInteger,
  type EntryTtlState,
  type EntryValueType,
  type EntryValueTypeFilter,
} from './entry-filters';

const { Title, Text } = Typography;

interface EntriesPageProps {
  readonly canBulkDelete?: boolean;
  readonly canOperate?: boolean;
  readonly selectedNamespace?: string;
  readonly selectedSetupId?: string;
}

/**
 * Key Browser (reference layout: filter card, Table with row selection, Modal forms). Entry
 * metadata is an RTK Query read keyed by setup, namespace, filters, and cursor; create and bulk
 * delete are mutations whose tag invalidations refresh the page from PostgreSQL truth.
 */
export function EntriesPage({ canOperate = false, canBulkDelete = canOperate, selectedNamespace, selectedSetupId }: EntriesPageProps) {
  const [draftPrefix, setDraftPrefix] = useState('');
  const [draftValueType, setDraftValueType] = useState<EntryValueTypeFilter>('ALL');
  const [draftTtlState, setDraftTtlState] = useState<EntryTtlState>('ALL_LIVE');
  const [query, setQuery] = useState<EntryQuery>({ ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50 });
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<BulkDeletePreview>();
  const [confirmation, setConfirmation] = useState('');
  const [operationStatus, setOperationStatus] = useState('');
  const [mutationProblem, setMutationProblem] = useState<ManagementQueryError>();
  const [now, setNow] = useState(0);
  const [creating, setCreating] = useState(false);
  const [newKey, setNewKey] = useState('');
  const [newType, setNewType] = useState<EntryValueType>('STRING');
  const [newValue, setNewValue] = useState('');
  const [newTtl, setNewTtl] = useState('');

  const scoped = selectedSetupId !== undefined && selectedNamespace !== undefined;
  const encodedNamespace = selectedNamespace === undefined ? '' : encodeNamespace(selectedNamespace);
  const entries = useGetEntriesQuery(
    { setupId: selectedSetupId ?? '', encodedNamespace, query: { ...query, cursor } },
    { skip: !scoped },
  );
  const [setEntry, setEntryState] = useSetEntryMutation();
  const [previewBulkDelete, previewState] = usePreviewEntryBulkDeleteMutation();
  const [executeBulkDelete, executeState] = useExecuteEntryBulkDeleteMutation();
  const mutating = setEntryState.isLoading || previewState.isLoading || executeState.isLoading;
  const page = entries.data;
  const loading = entries.isFetching;
  const problem = mutationProblem ?? (isManagementQueryError(entries.error) ? entries.error : undefined);

  useEffect(() => {
    if (preview === undefined) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [preview]);

  const applyFilters = () => {
    setHistory([]);
    setCursor(undefined);
    setSelected(new Set());
    setQuery({
      prefix: draftPrefix.trim() || undefined,
      valueType: draftValueType === 'ALL' ? undefined : draftValueType,
      ttlState: draftTtlState,
      sort: 'key:asc',
      limit: 50,
    });
  };

  const next = () => {
    const nextCursor = page?.nextCursor;
    if (nextCursor === null || nextCursor === undefined) return;
    setHistory((current) => [...current, cursor ?? null]);
    setCursor(nextCursor);
    setSelected(new Set());
  };

  const previous = () => {
    const prior = history.at(-1);
    if (prior === undefined) return;
    setHistory((current) => current.slice(0, -1));
    setCursor(prior ?? undefined);
    setSelected(new Set());
  };

  const previewDeletion = async (filterScope: boolean) => {
    if (selectedSetupId === undefined || page === undefined) return;
    setMutationProblem(undefined);
    setOperationStatus('');
    try {
      const selection = filterScope
        ? { selection: { type: 'FILTER' as const, prefix: query.prefix, valueType: query.valueType, ttlState: query.ttlState ?? 'ALL_LIVE' } }
        : { selection: { type: 'EXPLICIT' as const, targets: page.items.filter((item) => selected.has(item.encodedKey)).map((item) => ({ key: item.key, version: item.version })) } };
      const loaded = await previewBulkDelete({ setupId: selectedSetupId, encodedNamespace, selection }).unwrap();
      setPreview(loaded);
      setConfirmation('');
      setNow(Date.now());
    } catch (failure: unknown) {
      setMutationProblem(asQueryError(failure));
    }
  };

  const executeDeletion = async () => {
    if (selectedSetupId === undefined || preview === undefined) return;
    if (previewExpired(preview, now) || confirmation !== preview.confirmationPhrase) return;
    setMutationProblem(undefined);
    try {
      const result = await executeBulkDelete({ setupId: selectedSetupId, encodedNamespace, confirmation: { previewToken: preview.previewToken, confirmationPhrase: confirmation } }).unwrap();
      setOperationStatus(`Deleted ${result.deletedCount} of ${result.processedCount} previewed entries · conflicts ${result.conflictCount} · missing ${result.missingCount} · failed ${result.failedCount}.`);
      setPreview(undefined);
      setConfirmation('');
      setSelected(new Set());
    } catch (failure: unknown) {
      setMutationProblem(asQueryError(failure));
    }
  };

  const createEntry = async () => {
    if (selectedSetupId === undefined) return;
    setMutationProblem(undefined);
    try {
      const result = await setEntry({
        setupId: selectedSetupId,
        encodedNamespace,
        encodedKey: encodeKey(newKey),
        body: {
          value: cacheValueFor(newType, newValue),
          ttlMode: newTtl === '' ? 'REMOVE' : 'REPLACE',
          ttlMillis: newTtl === '' ? null : positiveInteger(newTtl),
          setMode: 'ONLY_IF_ABSENT',
        },
      }).unwrap();
      setOperationStatus(`Entry created at ${formatDisplayInstant(result.updatedAt)} · version ${result.version}.`);
      setCreating(false);
      setNewKey('');
      setNewValue('');
      setNewTtl('');
    } catch (failure: unknown) {
      setMutationProblem(asQueryError(failure));
    }
  };

  const toggle = (encodedKey: string) => {
    const nextSelection = new Set(selected);
    if (nextSelection.has(encodedKey)) nextSelection.delete(encodedKey); else nextSelection.add(encodedKey);
    setSelected(nextSelection);
  };

  if (selectedSetupId === undefined) {
    return <EntriesWorkspace><SetupScopeBar /><EmptyState title="Select a connected setup">Entry inspection requires an active database setup.</EmptyState></EntriesWorkspace>;
  }
  if (selectedNamespace === undefined) {
    return <EntriesWorkspace><SetupScopeBar /><EmptyState title="Select a namespace">Choose a namespace before browsing cache entries.</EmptyState></EntriesWorkspace>;
  }

  const canSelect = canBulkDelete;
  const columns = [
    ...(canSelect ? [{
      title: 'Selection', key: 'selection',
      render: (_value: unknown, item: EntryPage['items'][number]) => (
        <Checkbox aria-label={`Select ${item.key}`} checked={selected.has(item.encodedKey)} onChange={() => toggle(item.encodedKey)} />
      ),
    }] : []),
    { title: 'Key', key: 'key', render: (_value: unknown, item: EntryPage['items'][number]) => <Link to={`/keys/${item.encodedNamespace}/${item.encodedKey}`}>{item.key}</Link> },
    { title: 'Type', dataIndex: 'valueType', key: 'valueType' },
    { title: 'Size', dataIndex: 'sizeBytes', key: 'sizeBytes', render: (value: string) => formatDisplayBytes(value) },
    { title: 'Version', dataIndex: 'version', key: 'version', render: (value: string) => formatDecimal(value) },
    { title: 'Created', dataIndex: 'createdAt', key: 'createdAt', render: (value: string) => formatDisplayInstant(value) },
    { title: 'Updated', dataIndex: 'updatedAt', key: 'updatedAt', render: (value: string) => formatDisplayInstant(value) },
    { title: 'Expires', key: 'expires', render: (_value: unknown, item: EntryPage['items'][number]) => (item.ttl.expiresAt === null ? 'Never' : formatDisplayInstant(item.ttl.expiresAt)) },
    { title: 'Remaining TTL', key: 'ttl', render: (_value: unknown, item: EntryPage['items'][number]) => formatTtl(item.ttl.ttlMillis) },
    {
      title: 'Status', key: 'status',
      render: (_value: unknown, item: EntryPage['items'][number]) => (
        <Tag color={item.ttl.state === 'EXPIRED' ? 'red' : item.ttl.state === 'EXPIRING' ? 'gold' : 'green'}>{humanize(item.ttl.state)}</Tag>
      ),
    },
  ];

  return (
    <EntriesWorkspace
      actions={canOperate ? <Button icon={<PlusOutlined aria-hidden="true" />} onClick={() => setCreating(true)} type="primary">Create entry</Button> : undefined}
      namespace={selectedNamespace}
    >
      <SetupScopeBar />
      <Card className="filter-bar filter-bar--entries" size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={applyFilters}>
          <Form.Item htmlFor="entry-prefix" label="Key prefix">
            <Input id="entry-prefix" maxLength={1024} onChange={(event) => setDraftPrefix(event.target.value)} style={{ width: 240 }} value={draftPrefix} />
          </Form.Item>
          <Form.Item htmlFor="entry-value-type" label="Value type">
            <ValueSelect<EntryValueTypeFilter> id="entry-value-type" onChange={setDraftValueType} options={ENTRY_VALUE_TYPE_FILTER_OPTIONS} style={{ width: 140 }} value={draftValueType} />
          </Form.Item>
          <Form.Item htmlFor="entry-ttl-state" label="TTL state">
            <ValueSelect<EntryTtlState>
              id="entry-ttl-state"
              onChange={setDraftTtlState}
              options={ENTRY_TTL_STATE_OPTIONS}
              style={{ width: 170 }}
              value={draftTtlState}
            />
          </Form.Item>
          <Form.Item>
            <Button disabled={loading} htmlType="submit" type="primary">Apply filters</Button>
          </Form.Item>
        </Form>
      </Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {canBulkDelete && (
          <Space className="workspace__actions" wrap>
            <Button danger disabled={selected.size === 0 || mutating} onClick={() => void previewDeletion(false)}>Preview selected deletion</Button>
            <Button disabled={mutating} onClick={() => void previewDeletion(true)}>Preview matching-filter deletion</Button>
          </Space>
        )}
        {operationStatus !== '' && <Alert message={operationStatus} role="status" showIcon type="success" />}
        {problem !== undefined && <ProblemAlert problem={problem} stale={page !== undefined && mutationProblem === undefined} />}
        {page === undefined && entries.isLoading && <Text aria-busy="true">Loading entries…</Text>}
        {page !== undefined && page.items.length === 0 && <EmptyState title="No entries matched">Change the current key, value-type, or TTL filter.</EmptyState>}
        {page !== undefined && page.items.length > 0 && (
          <div aria-label="Entry results" className="table-scroll" role="region" tabIndex={0}>
            <Table
              columns={columns}
              dataSource={page.items}
              loading={loading}
              pagination={false}
              rowClassName={(item) => (selected.has(item.encodedKey) ? 'entry-row--selected' : '')}
              rowKey="encodedKey"
              size="small"
            />
          </div>
        )}
        {page !== undefined && (
          <nav aria-label="Entry pages" className="pagination">
            <Space>
              <Button disabled={history.length === 0 || loading} onClick={previous}>Previous page</Button>
              <Text>Page {history.length + 1}</Text>
              <Button disabled={!page.hasMore || loading} onClick={next}>Next page</Button>
            </Space>
          </nav>
        )}
      </Space>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setPreview(undefined)}
        open={preview !== undefined}
        title={<Title id="bulk-entry-title" level={2} style={{ margin: 0, fontSize: 20 }}>Confirm bulk entry deletion</Title>}
      >
        {preview !== undefined && (
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Text>Setup <strong>{preview.setupId}</strong> · namespace <strong>{preview.namespace}</strong></Text>
            <Text>Resolved {preview.resolvedCount} entries ({formatDisplayBytes(preview.totalBytes)}). Preview expires at {formatDisplayInstant(preview.expiresAt)}.</Text>
            {preview.sampleKeys.length > 0 && <Text>Sample: {preview.sampleKeys.join(', ')}</Text>}
            <Text>Required phrase: <strong>{preview.confirmationPhrase}</strong></Text>
            {previewExpired(preview, now) && <Alert description="Close this dialog and request a new preview." message="Preview expired" role="alert" showIcon type="error" />}
            <Form layout="vertical">
              <Form.Item htmlFor="bulk-entry-confirmation" label="Type confirmation phrase">
                <Input autoComplete="off" id="bulk-entry-confirmation" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} />
              </Form.Item>
            </Form>
            <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
              <Button onClick={() => setPreview(undefined)}>Cancel</Button>
              <Button danger disabled={previewExpired(preview, now) || mutating || confirmation !== preview.confirmationPhrase} onClick={() => void executeDeletion()} type="primary">Delete previewed entries</Button>
            </Space>
          </Space>
        )}
      </Modal>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setCreating(false)}
        open={creating}
        title={<Title id="create-entry-title" level={2} style={{ margin: 0, fontSize: 20 }}>Create entry</Title>}
      >
        <Text>The key must be absent when PostgreSQL commits this request.</Text>
        <Form className="form-grid" layout="vertical" onFinish={() => void createEntry()} style={{ marginTop: 16 }}>
          <Form.Item htmlFor="new-entry-key" label="Key"><Input id="new-entry-key" maxLength={1024} onChange={(event) => setNewKey(event.target.value)} required value={newKey} /></Form.Item>
          <Form.Item htmlFor="new-entry-type" label="Value type"><ValueSelect<EntryValueType> id="new-entry-type" onChange={setNewType} options={ENTRY_VALUE_TYPE_OPTIONS} value={newType} /></Form.Item>
          <Form.Item htmlFor="new-entry-ttl" label="TTL milliseconds (blank for persistent)"><Input id="new-entry-ttl" min={1} onChange={(event) => setNewTtl(event.target.value)} type="number" value={newTtl} /></Form.Item>
          <Form.Item htmlFor="new-entry-value" label="Value"><Input.TextArea id="new-entry-value" onChange={(event) => setNewValue(event.target.value)} required rows={4} value={newValue} /></Form.Item>
          <Space className="modal__actions" style={{ justifyContent: 'flex-end', width: '100%' }}>
            <Button onClick={() => setCreating(false)}>Cancel</Button>
            <Button disabled={mutating} htmlType="submit" type="primary">Create entry</Button>
          </Space>
        </Form>
      </Modal>
    </EntriesWorkspace>
  );
}

function EntriesWorkspace({ namespace, actions, children }: { readonly namespace?: string; readonly actions?: ReactNode; readonly children: ReactNode }) {
  return (
    <section aria-labelledby="entries-title" className="workspace">
      <div className="workspace__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Text className="workspace__context" type="secondary">Metadata-only entry inspection</Text>
          <Title id="entries-title" level={1} style={{ marginTop: 4 }}>Key Browser</Title>
          {namespace !== undefined && <Text>Namespace: <strong>{namespace}</strong></Text>}
        </div>
        {actions !== undefined && <Space className="workspace__actions">{actions}</Space>}
      </div>
      {children}
    </section>
  );
}

function EmptyState({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  return <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>{title}</Title>}><Text type="secondary">{children}</Text></Empty></Card>;
}

function ProblemAlert({ problem, stale }: { readonly problem: ManagementQueryError; readonly stale: boolean }) {
  return (
    <Alert
      description={<>{problem.message}{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</>}
      message={stale ? `Stale data · ${problem.code}` : problem.code}
      role="alert"
      showIcon
      type={stale ? 'warning' : 'error'}
    />
  );
}

function asQueryError(failure: unknown): ManagementQueryError {
  if (isManagementQueryError(failure)) return failure;
  if (failure instanceof EntryInputError) return { status: 400, code: failure.code, message: failure.message };
  return { status: 0, code: 'CONNECTION_FAILED', message: 'Entry administration could not be completed' };
}

function previewExpired(preview: BulkDeletePreview, now: number): boolean {
  return Date.parse(preview.expiresAt) <= now;
}
