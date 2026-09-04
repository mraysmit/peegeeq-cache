import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Descriptions, Empty, Row, Space, Table, Typography } from 'antd';
import { useEffect, type ReactNode } from 'react';

import type { Overview } from '../../api/inspection-schemas';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { formatDisplayInstant } from '../../presentation/display-time';
import { useLiveStore } from '../../state/live-store';
import { loadPreferences } from '../../state/preferences';
import { SetupScopeBar } from '../../components/common/SetupScopeBar';
import { StatCard } from '../../components/common/StatCard';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import {
  useGetActivityQuery,
  useGetDatabaseMonitoringQuery,
  useGetOverviewQuery,
  useGetRuntimeMonitoringQuery,
} from '../../store/api/inspectionApi';
import { formatDecimal, formatDuration, titleCase } from '../../presentation/display-format';
import { OverviewMonitoring } from './OverviewMonitoring';
import { SessionTrendChart } from './SessionTrendChart';

const { Title, Text } = Typography;

interface OverviewPageProps {
  readonly selectedSetupId?: string;
}

const valueTypes = ['STRING', 'JSON', 'LONG', 'BYTES'] as const;
const EMPTY_TREND: readonly never[] = [];

/**
 * Overview (reference: `peegeeq-management-ui/pages/Overview.tsx`): scope bar, refresh, a row of
 * StatCards, detail cards, a Recharts trend, monitoring panels, and the top-namespace table.
 * All reads are RTK Query hooks polling at the operator's refresh preference; the trend is the
 * only local state and is scoped to the console session (design §3.1/§8.2).
 */
export function OverviewPage({ selectedSetupId }: OverviewPageProps) {
  const setupId = selectedSetupId ?? '';
  const skip = selectedSetupId === undefined;
  const pollingInterval = loadPreferences().refreshSeconds * 1_000;
  const overview = useGetOverviewQuery({ setupId }, { skip, pollingInterval });
  const database = useGetDatabaseMonitoringQuery({ setupId }, { skip, pollingInterval });
  const runtime = useGetRuntimeMonitoringQuery({ setupId }, { skip, pollingInterval });
  const activity = useGetActivityQuery({ setupId, query: { limit: 20 } }, { skip, pollingInterval });
  // Session-scoped trend lives in the Zustand live store (reference: chart data in the
  // management store), fed from each new snapshot and never persisted (design §8.2).
  const recordSnapshot = useLiveStore((state) => state.recordSnapshot);
  const points = useLiveStore((state) => (selectedSetupId === undefined ? undefined : state.trend[selectedSetupId])) ?? EMPTY_TREND;
  useEffect(() => {
    if (selectedSetupId !== undefined && overview.data !== undefined) recordSnapshot(selectedSetupId, overview.data);
  }, [overview.data, recordSnapshot, selectedSetupId]);

  const refresh = () => {
    void overview.refetch();
    void database.refetch();
    void runtime.refetch();
    void activity.refetch();
  };
  const refreshing = overview.isFetching || database.isFetching || runtime.isFetching || activity.isFetching;
  const snapshot = overview.data;
  const problem = queryError(overview.error);

  if (selectedSetupId === undefined) {
    return (
      <Workspace title="Overview">
        <SetupScopeBar />
        <Card>
          <Empty description={<Title level={2} style={{ fontSize: 18 }}>Select a connected setup</Title>}>
            <Text type="secondary">Database-wide cache information is available after a setup is selected.</Text>
          </Empty>
        </Card>
      </Workspace>
    );
  }

  if (snapshot === undefined && overview.isLoading) {
    return (
      <Workspace title="Overview">
        <SetupScopeBar />
        <Text aria-busy="true">Loading database overview…</Text>
      </Workspace>
    );
  }

  if (snapshot === undefined) {
    return (
      <Workspace actions={<RefreshButton loading={refreshing} onRefresh={refresh} />} title="Overview">
        <SetupScopeBar />
        {problem !== undefined && <ProblemNotice problem={problem} stale={false} />}
      </Workspace>
    );
  }

  return (
    <Workspace
      actions={<RefreshButton loading={refreshing} onRefresh={refresh} />}
      description={(
        <>
          <strong>Database-wide snapshot</strong>
          {' · Observed '}
          <time aria-label="Snapshot observed at" dateTime={snapshot.observedAt}>
            {formatDisplayInstant(snapshot.observedAt)}
          </time>
        </>
      )}
      title="Overview"
    >
      <SetupScopeBar />
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {problem !== undefined && <ProblemNotice problem={problem} stale />}
        <Text className="scope-label" type="secondary">
          Values are database-wide unless a panel is explicitly labelled management-server-local.
        </Text>

        <Row aria-label="Database overview totals" gutter={[16, 16]} role="group">
          <Col lg={6} sm={12} xs={24}><StatCard detail={`${snapshot.health.latencyMillis} ms round trip`} title="Setup status" value={titleCase(snapshot.health.status)} /></Col>
          <Col lg={6} sm={12} xs={24}><StatCard title="Live cache entries" value={formatDecimal(snapshot.totals.liveEntryCount)} /></Col>
          <Col lg={6} sm={12} xs={24}><StatCard title="Live counters" value={formatDecimal(snapshot.totals.liveCounterCount)} /></Col>
          <Col lg={6} sm={12} xs={24}><StatCard title="Active locks" value={formatDecimal(snapshot.totals.activeLockCount)} /></Col>
          <Col lg={6} sm={12} xs={24}><StatCard title="Expired entries awaiting cleanup" value={formatDecimal(snapshot.totals.expiredEntryCount)} /></Col>
          <Col lg={6} sm={12} xs={24}><StatCard title="Expired counters awaiting cleanup" value={formatDecimal(snapshot.totals.expiredCounterCount)} /></Col>
          <Col lg={6} sm={12} xs={24}>
            {snapshot.databaseStats.databaseBytes.availability === 'AVAILABLE'
              ? <StatCard title="Database storage" value={formatDisplayBytes(snapshot.databaseStats.databaseBytes.value)} />
              : <StatCard detail={snapshot.databaseStats.databaseBytes.reason} title="Database storage" value="Unavailable" />}
          </Col>
          <Col lg={6} sm={12} xs={24}>
            {snapshot.totals.schemaBytes.availability === 'AVAILABLE'
              ? <StatCard title="Cache schema storage" value={formatDisplayBytes(snapshot.totals.schemaBytes.value)} />
              : <StatCard detail={snapshot.totals.schemaBytes.reason} title="Cache schema storage" value="Unavailable" />}
          </Col>
        </Row>

        <Row gutter={[16, 16]}>
          <Col lg={12} xs={24}>
            <DetailSection heading="Expiry and cleanup" id="expiry">
              <Descriptions.Item key="sweeper" label="Sweeper">{snapshot.expiry.sweeperEnabled ? 'Enabled' : 'Disabled'}</Descriptions.Item>
              <Descriptions.Item key="lag" label="Oldest backlog lag">{snapshot.expiry.oldestExpiredRowLagMillis === null ? 'None' : formatDuration(snapshot.expiry.oldestExpiredRowLagMillis)}</Descriptions.Item>
              <Descriptions.Item key="expired-entries" label="Exact expired entries">{formatDecimal(snapshot.expiryStats.expiredEntryCount)}</Descriptions.Item>
              <Descriptions.Item key="expired-counters" label="Exact expired counters">{formatDecimal(snapshot.expiryStats.expiredCounterCount)}</Descriptions.Item>
              <Descriptions.Item key="deleted" label="Last sweep deleted">{formatDecimal(snapshot.expiry.lastSweepDeletedRows)}</Descriptions.Item>
              <Descriptions.Item key="last-sweep" label="Last sweep">{snapshot.expiry.lastSweepAt === null ? 'Not observed' : formatDisplayInstant(snapshot.expiry.lastSweepAt)}</Descriptions.Item>
            </DetailSection>
          </Col>
          <Col lg={12} xs={24}>
            <DetailSection heading="Entry value types" id="types">
              {valueTypes.map((type) => (
                <Descriptions.Item key={type} label={titleCase(type)}>
                  {snapshot.valueTypeCounts[type] === undefined ? 'Not reported' : formatDecimal(snapshot.valueTypeCounts[type])}
                </Descriptions.Item>
              ))}
            </DetailSection>
          </Col>
        </Row>

        <SessionTrendChart points={points} />

        <OverviewMonitoring
          activity={activity.data}
          activityProblem={queryError(activity.error)}
          database={database.data}
          databaseProblem={queryError(database.error)}
          runtime={runtime.data}
          runtimeProblem={queryError(runtime.error)}
        />

        <section aria-labelledby="namespace-overview-heading" className="overview-section">
          <Card title={<Title id="namespace-overview-heading" level={2} style={{ margin: 0, fontSize: 18 }}>Namespace overview</Title>}>
            {snapshot.topNamespaces.length === 0 ? (
              <Text>No namespaces were observed in this database snapshot.</Text>
            ) : (
              <div aria-label="Namespace overview results" className="table-scroll" role="region" tabIndex={0}>
                <Table
                  columns={[
                    {
                      title: 'Namespace',
                      dataIndex: 'namespace',
                      key: 'namespace',
                      render: (value: string, row: Overview['topNamespaces'][number]) => (
                        <><strong>{value}</strong><br /><Text type="secondary">Observed {formatDisplayInstant(row.observedAt)}</Text></>
                      ),
                    },
                    { title: 'Live entries', dataIndex: 'liveEntryCount', key: 'liveEntryCount', render: (value: string) => formatDecimal(value) },
                    { title: 'Counters', dataIndex: 'liveCounterCount', key: 'liveCounterCount', render: (value: string) => formatDecimal(value) },
                    { title: 'Locks', dataIndex: 'activeLockCount', key: 'activeLockCount', render: (value: string) => formatDecimal(value) },
                    { title: 'Expired', dataIndex: 'expiredEntryCount', key: 'expiredEntryCount', render: (value: string) => formatDecimal(value) },
                    { title: 'Storage', dataIndex: 'estimatedStorageBytes', key: 'estimatedStorageBytes', render: (value: string) => formatDisplayBytes(value) },
                  ]}
                  dataSource={snapshot.topNamespaces.map((namespace) => ({ ...namespace, key: namespace.encodedNamespace }))}
                  pagination={false}
                  size="small"
                />
              </div>
            )}
          </Card>
        </section>
      </Space>
    </Workspace>
  );
}

function Workspace({ title, description, actions, children }: {
  readonly title: string;
  readonly description?: ReactNode;
  readonly actions?: ReactNode;
  readonly children: ReactNode;
}) {
  return (
    <section aria-labelledby="overview-title" className="workspace">
      <div className="workspace__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Text className="workspace__context" type="secondary">Selected setup inspection</Text>
          <Title id="overview-title" level={1} style={{ marginTop: 4 }}>{title}</Title>
          {description !== undefined && <Text>{description}</Text>}
        </div>
        {actions}
      </div>
      {children}
    </section>
  );
}

function DetailSection({ children, heading, id }: { readonly children: ReactNode; readonly heading: string; readonly id: string }) {
  return (
    <section aria-labelledby={`${id}-heading`} className="overview-panel">
      <Card title={<Title id={`${id}-heading`} level={2} style={{ margin: 0, fontSize: 18 }}>{heading}</Title>}>
        <Descriptions column={1} size="small">{children}</Descriptions>
      </Card>
    </section>
  );
}

function RefreshButton({ loading, onRefresh }: { readonly loading: boolean; readonly onRefresh: () => void }) {
  return (
    <Button disabled={loading} icon={<ReloadOutlined aria-hidden="true" />} onClick={onRefresh}>
      {loading ? 'Refreshing…' : 'Refresh overview'}
    </Button>
  );
}

function ProblemNotice({ problem, stale }: { readonly problem: ManagementQueryError; readonly stale: boolean }) {
  return (
    <Alert
      description={(
        <>
          <p>{problem.message}</p>
          {problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}
        </>
      )}
      message={stale ? `Stale data · ${problem.code}` : problem.code}
      role="alert"
      showIcon
      type={stale ? 'warning' : 'error'}
    />
  );
}

function queryError(error: unknown): ManagementQueryError | undefined {
  return isManagementQueryError(error) ? error : undefined;
}
