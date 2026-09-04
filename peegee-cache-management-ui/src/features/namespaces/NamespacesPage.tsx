import { DownloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Form, Input, Space, Table, Typography } from 'antd';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { NamespaceQuery } from '../../api/inspection-client';
import type { NamespacePage } from '../../api/inspection-schemas';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { formatDecimal } from '../../presentation/display-format';
import { formatDisplayInstant } from '../../presentation/display-time';
import { SetupScopeBar } from '../../components/common/SetupScopeBar';
import { ValueSelect } from '../../components/common/ValueSelect';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { useGetNamespacesQuery, useLazyExportNamespacesQuery } from '../../store/api/inspectionApi';

const { Title, Text } = Typography;

interface NamespacesPageProps {
  readonly selectedSetupId?: string;
}

import { NAMESPACE_SORT_OPTIONS, NAMESPACE_STATUS_OPTIONS, type NamespaceSort, type NamespaceStatus } from './namespace-filters';

/**
 * Namespaces (reference layout: filter card + Table + cursor pagination). The opaque cursor stack
 * is component state; every page is an RTK Query read keyed by setup, query, and cursor, so
 * back-navigation is served from cache and re-validated by the server on refetch.
 */
export function NamespacesPage({ selectedSetupId }: NamespacesPageProps) {
  const [draftPrefix, setDraftPrefix] = useState('');
  const [draftStatus, setDraftStatus] = useState<NamespaceStatus>('ALL');
  const [draftSort, setDraftSort] = useState<NamespaceSort>('namespace:asc');
  const [query, setQuery] = useState<NamespaceQuery>({ status: 'ALL', sort: 'namespace:asc', limit: 50 });
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [notice, setNotice] = useState<string>();
  const [exportProblem, setExportProblem] = useState<ManagementQueryError>();
  const page = useGetNamespacesQuery(
    { setupId: selectedSetupId ?? '', query: { ...query, cursor } },
    { skip: selectedSetupId === undefined },
  );
  const [exportNamespaces] = useLazyExportNamespacesQuery();

  const applyFilters = () => {
    setHistory([]);
    setCursor(undefined);
    setNotice(undefined);
    setQuery({ prefix: draftPrefix.trim() || undefined, status: draftStatus, sort: draftSort, limit: 50 });
  };

  const next = () => {
    const nextCursor = page.data?.nextCursor;
    if (nextCursor === null || nextCursor === undefined) return;
    setHistory((current) => [...current, cursor ?? null]);
    setCursor(nextCursor);
  };

  const previous = () => {
    const prior = history.at(-1);
    if (prior === undefined) return;
    setHistory((current) => current.slice(0, -1));
    setCursor(prior ?? undefined);
  };

  const exportCurrent = async () => {
    if (selectedSetupId === undefined) return;
    setNotice(undefined);
    setExportProblem(undefined);
    try {
      const exported = await exportNamespaces({ setupId: selectedSetupId, query: { prefix: query.prefix, status: query.status, sort: query.sort } }).unwrap();
      downloadExport(exported, selectedSetupId);
      setNotice(`Exported ${exported.items.length.toLocaleString('en-US')} namespace${exported.items.length === 1 ? '' : 's'}${exported.truncated ? ' (server limit reached)' : ''}.`);
    } catch (failure: unknown) {
      setExportProblem(isManagementQueryError(failure) ? failure : { status: 0, code: 'CONNECTION_FAILED', message: 'Namespace export could not be completed' });
    }
  };

  const problem = exportProblem ?? (isManagementQueryError(page.error) ? page.error : undefined);
  const loading = page.isFetching;
  const data = page.data;

  if (selectedSetupId === undefined) {
    return (
      <NamespaceWorkspace>
        <SetupScopeBar />
        <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Select a connected setup</Title>}><Text type="secondary">Namespace inspection requires an active database setup.</Text></Empty></Card>
      </NamespaceWorkspace>
    );
  }

  return (
    <NamespaceWorkspace actions={<Button disabled={data === undefined} icon={<DownloadOutlined aria-hidden="true" />} onClick={() => void exportCurrent()}>Export namespaces</Button>}>
      <SetupScopeBar />
      <Card className="filter-bar" size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline" onFinish={applyFilters}>
          <Form.Item htmlFor="namespace-prefix" label="Namespace prefix">
            <Input id="namespace-prefix" maxLength={128} onChange={(event) => setDraftPrefix(event.target.value)} style={{ width: 240 }} value={draftPrefix} />
          </Form.Item>
          <Form.Item htmlFor="namespace-status" label="Status">
            <ValueSelect<NamespaceStatus> id="namespace-status" onChange={setDraftStatus} options={NAMESPACE_STATUS_OPTIONS} style={{ width: 180 }} value={draftStatus} />
          </Form.Item>
          <Form.Item htmlFor="namespace-sort" label="Sort">
            <ValueSelect<NamespaceSort> id="namespace-sort" onChange={setDraftSort} options={NAMESPACE_SORT_OPTIONS} style={{ width: 160 }} value={draftSort} />
          </Form.Item>
          <Form.Item>
            <Button disabled={loading} htmlType="submit" type="primary">Apply filters</Button>
          </Form.Item>
        </Form>
      </Card>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {problem !== undefined && (
          <Alert
            description={<>{problem.message}{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</>}
            message={data === undefined ? problem.code : `Stale data · ${problem.code}`}
            role="alert"
            showIcon
            type={data === undefined ? 'error' : 'warning'}
          />
        )}
        {notice !== undefined && <Alert message={notice} role="status" showIcon type="success" />}
        {data === undefined && page.isLoading && <Text aria-busy="true">Loading namespaces…</Text>}
        {data !== undefined && data.items.length === 0 && (
          <Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>No namespaces matched</Title>}><Text type="secondary">Change the current prefix or status filter.</Text></Empty></Card>
        )}
        {data !== undefined && data.items.length > 0 && (
          <div aria-label="Namespace results" className="table-scroll" role="region" tabIndex={0}>
            <Table
              columns={[
                { title: 'Namespace', key: 'namespace', render: (_value: unknown, item: NamespacePage['items'][number]) => <Link to={`/namespaces/${item.encodedNamespace}`}>{item.namespace}</Link> },
                { title: 'Live entries', dataIndex: 'liveEntryCount', key: 'liveEntryCount', render: (value: string) => formatDecimal(value) },
                { title: 'Counters', dataIndex: 'liveCounterCount', key: 'liveCounterCount', render: (value: string) => formatDecimal(value) },
                { title: 'Locks', dataIndex: 'activeLockCount', key: 'activeLockCount', render: (value: string) => formatDecimal(value) },
                { title: 'Expiring', dataIndex: 'expiringEntryCount', key: 'expiringEntryCount', render: (value: string) => formatDecimal(value) },
                { title: 'Expired', dataIndex: 'expiredEntryCount', key: 'expiredEntryCount', render: (value: string) => formatDecimal(value) },
                { title: 'Storage', dataIndex: 'estimatedStorageBytes', key: 'estimatedStorageBytes', render: (value: string) => formatDisplayBytes(value) },
                { title: 'Observed', dataIndex: 'observedAt', key: 'observedAt', render: (value: string) => formatDisplayInstant(value) },
              ]}
              dataSource={data.items.map((item) => ({ ...item, key: item.encodedNamespace }))}
              loading={loading}
              pagination={false}
              size="small"
            />
          </div>
        )}
        {data !== undefined && (
          <nav aria-label="Namespace pages" className="pagination">
            <Space>
              <Button disabled={history.length === 0 || loading} onClick={previous}>Previous page</Button>
              <Text>Page {history.length + 1}</Text>
              <Button disabled={!data.hasMore || loading} onClick={next}>Next page</Button>
            </Space>
          </nav>
        )}
      </Space>
    </NamespaceWorkspace>
  );
}

function NamespaceWorkspace({ actions, children }: { readonly actions?: ReactNode; readonly children: ReactNode }) {
  return (
    <section aria-labelledby="namespaces-title" className="workspace">
      <div className="workspace__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Text className="workspace__context" type="secondary">Database-wide inspection</Text>
          <Title id="namespaces-title" level={1} style={{ marginTop: 4 }}>Namespaces</Title>
          <Text>Cursor-paginated namespace metadata for the selected setup.</Text>
        </div>
        {actions}
      </div>
      {children}
    </section>
  );
}

function downloadExport(exported: unknown, setupId: string): void {
  if (typeof URL.createObjectURL !== 'function') return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${setupId}-namespaces.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}
