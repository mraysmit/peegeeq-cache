import { Alert, Card, Col, Descriptions, Row, Table, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

import type { ActivityPage, DatabaseMonitoring, RuntimeMonitoring } from '../../api/inspection-schemas';
import type { ManagementQueryError } from '../../store/api/apiBase';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { formatDecimal, formatDuration, humanize } from '../../presentation/display-format';
import { formatDisplayInstant } from '../../presentation/display-time';

interface OverviewMonitoringProps {
  readonly activity?: ActivityPage;
  readonly activityProblem?: ManagementQueryError;
  readonly database?: DatabaseMonitoring;
  readonly databaseProblem?: ManagementQueryError;
  readonly runtime?: RuntimeMonitoring;
  readonly runtimeProblem?: ManagementQueryError;
}

type AvailableLong = DatabaseMonitoring['tableBytes'];

/**
 * Database monitoring, management runtime, and recent activity panels shared by Overview and
 * Monitoring (reference layout: Card + Descriptions + Table). Each panel is a `<section>` whose
 * accessible name is its heading so the browser suite's `section(page, heading)` helper resolves.
 */
export function OverviewMonitoring({ activity, activityProblem, database, databaseProblem, runtime, runtimeProblem }: OverviewMonitoringProps) {
  return (
    <>
      <MonitoringSection heading="Database monitoring" id="database-monitoring" observedAt={database?.observedAt} problem={databaseProblem} scope="Database-wide" stale={database !== undefined}>
        {database === undefined ? (
          <Typography.Text aria-busy="true">Loading database monitoring…</Typography.Text>
        ) : (
          <Row gutter={[16, 16]}>
            <Col lg={8} xs={24}>
              <MonitoringPanel title="Database storage">
                {availableItem('Table storage', database.tableBytes, true)}
                {availableItem('Index storage', database.indexBytes, true)}
                {availableItem('Total schema storage', database.schemaBytes, true)}
              </MonitoringPanel>
            </Col>
            <Col lg={8} xs={24}>
              <MonitoringPanel title="Database rows and maintenance">
                {availableItem('Live rows', database.liveRows)}
                {availableItem('Expired rows', database.expiredRows)}
                {availableItem('Dead tuples', database.deadTuples)}
                <Descriptions.Item key="expiry-backlog" label="Expiry backlog">{formatDecimal(database.expiryBacklog)}</Descriptions.Item>
                <Descriptions.Item key="oldest-lag" label="Oldest expired lag">{database.oldestExpiredRowLagMillis === null ? 'None' : formatDuration(database.oldestExpiredRowLagMillis)}</Descriptions.Item>
                <Descriptions.Item key="last-vacuum" label="Last vacuum">{formatOptionalInstant(database.lastVacuumAt)}</Descriptions.Item>
                <Descriptions.Item key="last-autovacuum" label="Last autovacuum">{formatOptionalInstant(database.lastAutovacuumAt)}</Descriptions.Item>
              </MonitoringPanel>
            </Col>
            <Col lg={8} xs={24}>
              <MonitoringPanel title="Database connections">
                {availableItem('All database connections', database.databaseConnections)}
                {availableItem('PeeGeeQ Cache connections', database.cacheConnections)}
              </MonitoringPanel>
            </Col>
          </Row>
        )}
      </MonitoringSection>

      <MonitoringSection heading="Management runtime" id="runtime-monitoring" observedAt={runtime?.observedAt} problem={runtimeProblem} scope="Management-server-local" stale={runtime !== undefined}>
        {runtime === undefined ? (
          <Typography.Text aria-busy="true">Loading management runtime…</Typography.Text>
        ) : (
          <>
            <Row gutter={[16, 16]}>
              <Col lg={8} xs={24}>
                <MonitoringPanel title="Runtime state">
                  <Descriptions.Item key="lifecycle" label="Lifecycle">{humanize(runtime.lifecycleState)}</Descriptions.Item>
                  <Descriptions.Item key="active-operations" label="Active operations">{formatDecimal(runtime.activeOperations)}</Descriptions.Item>
                  <Descriptions.Item key="pubsub" label="Pub/Sub subscriptions">{formatDecimal(runtime.pubSubSubscriptions)}</Descriptions.Item>
                  <Descriptions.Item key="sse" label="SSE clients">{formatDecimal(runtime.sseClients)}</Descriptions.Item>
                  <Descriptions.Item key="ws" label="WebSocket clients">{formatDecimal(runtime.webSocketClients)}</Descriptions.Item>
                  <Descriptions.Item key="retained" label="Retained payload">{formatDisplayBytes(runtime.retainedPayloadBytes)}</Descriptions.Item>
                </MonitoringPanel>
              </Col>
              <Col lg={8} xs={24}>
                <MonitoringPanel title="Management pool">
                  {availableItem('Active', runtime.pool.active)}
                  {availableItem('Idle', runtime.pool.idle)}
                  {availableItem('Pending', runtime.pool.pending)}
                  {availableItem('Maximum', runtime.pool.maximum)}
                </MonitoringPanel>
              </Col>
              <Col lg={8} xs={24}>
                <MonitoringPanel title="Audit and expiry">
                  <Descriptions.Item key="audit-queue" label="Audit queue">{`${formatDecimal(runtime.auditQueue.depth)} / ${formatDecimal(runtime.auditQueue.capacity)}`}</Descriptions.Item>
                  <Descriptions.Item key="accepting" label="Accepting mutations">{runtime.auditQueue.acceptingMutations ? 'Yes' : 'No'}</Descriptions.Item>
                  <Descriptions.Item key="owns-sweeper" label="Runtime owns sweeper">{runtime.expirySweeper.ownedByRuntime ? 'Yes' : 'No'}</Descriptions.Item>
                  <Descriptions.Item key="sweeper-running" label="Sweeper running">{runtime.expirySweeper.running ? 'Yes' : 'No'}</Descriptions.Item>
                  <Descriptions.Item key="last-sweep" label="Last sweep">{formatOptionalInstant(runtime.expirySweeper.lastSweepAt)}</Descriptions.Item>
                </MonitoringPanel>
              </Col>
            </Row>
            {runtime.operations.length > 0 && (
              <div aria-label="Operation telemetry" className="table-scroll" role="region" style={{ marginTop: 16 }} tabIndex={0}>
                <Table
                  columns={[
                    { title: 'Operation', dataIndex: 'operation', key: 'operation' },
                    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => humanize(status) },
                    { title: 'Count', dataIndex: 'count', key: 'count', render: (count: string) => formatDecimal(count) },
                    { title: 'Errors', dataIndex: 'errorCount', key: 'errorCount', render: (count: string) => formatDecimal(count) },
                    { title: 'Total latency', dataIndex: 'latencyMillis', key: 'latencyMillis', render: (latency: string) => formatDuration(BigInt(latency)) },
                  ]}
                  dataSource={runtime.operations.map((operation) => ({ ...operation, key: `${operation.operation}:${operation.status}` }))}
                  pagination={false}
                  size="small"
                  title={() => 'Management-server-local operation aggregates'}
                />
              </div>
            )}
          </>
        )}
      </MonitoringSection>

      <MonitoringSection extra={<Typography.Text className="live-status" type="secondary">Refreshes every 15 seconds</Typography.Text>} heading="Recent activity" id="recent-activity" problem={activityProblem} scope="Management-server-local · bounded · non-authoritative" stale={activity !== undefined}>
        {activity === undefined ? (
          <Typography.Text aria-busy="true">Loading recent activity…</Typography.Text>
        ) : activity.items.length === 0 ? (
          <Typography.Text>No activity is retained for this setup in the current management process.</Typography.Text>
        ) : (
          <div aria-label="Recent activity events" className="table-scroll" role="region" tabIndex={0}>
            <Table
              columns={[
                { title: 'Occurred', dataIndex: 'occurredAt', key: 'occurredAt', render: (value: string) => formatDisplayInstant(value) },
                { title: 'Actor', dataIndex: 'actor', key: 'actor' },
                { title: 'Action', dataIndex: 'action', key: 'action', render: (value: string) => humanize(value) },
                { title: 'Outcome', dataIndex: 'outcome', key: 'outcome', render: (value: string) => <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'REJECTED' ? 'gold' : value === 'FAILED' ? 'red' : 'default'}>{humanize(value)}</Tag> },
                { title: 'Resource', key: 'resource', render: (_value: unknown, event: ActivityPage['items'][number]) => humanize(event.resource.type) },
                { title: 'Summary', dataIndex: 'summary', key: 'summary', render: (value: string) => value || 'No summary' },
                { title: 'Correlation', dataIndex: 'correlationId', key: 'correlationId' },
              ]}
              dataSource={activity.items.slice(0, 20).map((event) => ({ ...event, key: event.eventId }))}
              pagination={false}
              size="small"
            />
          </div>
        )}
      </MonitoringSection>
    </>
  );
}

function MonitoringSection({ children, extra, heading, id, observedAt, problem, scope, stale }: {
  readonly children: ReactNode;
  readonly extra?: ReactNode;
  readonly heading: string;
  readonly id: string;
  readonly observedAt?: string;
  readonly problem?: ManagementQueryError;
  readonly scope: string;
  readonly stale: boolean;
}) {
  return (
    <section aria-labelledby={`${id}-heading`} className="overview-section">
      <Card
        extra={observedAt !== undefined ? <ObservedAt value={observedAt} /> : extra}
        title={(
          <>
            <Typography.Text className="panel-scope" style={{ display: 'block', fontSize: 12 }} type="secondary">{scope}</Typography.Text>
            <Typography.Title id={`${id}-heading`} level={2} style={{ margin: 0, fontSize: 18 }}>{heading}</Typography.Title>
          </>
        )}
      >
        {problem !== undefined && (
          <Alert
            message={<span><strong>{stale ? 'Stale' : 'Unavailable'} · {problem.code}</strong> {problem.message}</span>}
            role="status"
            showIcon
            style={{ marginBottom: 16 }}
            type={stale ? 'warning' : 'error'}
          />
        )}
        {children}
      </Card>
    </section>
  );
}

function MonitoringPanel({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  const id = `monitoring-${title.toLowerCase().replaceAll(/[^a-z0-9]+/gu, '-')}`;
  return (
    <section aria-labelledby={id} className="overview-panel">
      <Typography.Title id={id} level={3} style={{ fontSize: 14, marginTop: 0 }}>{title}</Typography.Title>
      <Descriptions column={1} size="small">{children}</Descriptions>
    </section>
  );
}

/** Descriptions.Item must be a direct child of Descriptions, so items are built as elements, not components. */
function availableItem(label: string, value: AvailableLong, bytes = false) {
  if (value.availability === 'UNAVAILABLE') {
    return <Descriptions.Item key={label} label={label}>Unavailable <Typography.Text type="secondary">{value.reason}</Typography.Text></Descriptions.Item>;
  }
  return <Descriptions.Item key={label} label={label}>{bytes ? formatDisplayBytes(value.value) : formatDecimal(value.value)}</Descriptions.Item>;
}

function ObservedAt({ value }: { readonly value: string }) {
  return <Typography.Text className="observed-at" type="secondary">Observed <time dateTime={value}>{formatDisplayInstant(value)}</time></Typography.Text>;
}

function formatOptionalInstant(value: string | null): string {
  return value === null ? 'Not observed' : formatDisplayInstant(value);
}
