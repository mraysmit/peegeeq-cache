import type {
  ActivityPage,
  DatabaseMonitoring,
  RuntimeMonitoring,
} from '../../api/inspection-schemas';
import type { ReactNode } from 'react';
import type { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';

interface OverviewMonitoringProps {
  readonly activity?: ActivityPage;
  readonly activityProblem?: ManagementClientError;
  readonly database?: DatabaseMonitoring;
  readonly databaseProblem?: ManagementClientError;
  readonly runtime?: RuntimeMonitoring;
  readonly runtimeProblem?: ManagementClientError;
}

type AvailableLong = DatabaseMonitoring['tableBytes'];

export function OverviewMonitoring({
  activity,
  activityProblem,
  database,
  databaseProblem,
  runtime,
  runtimeProblem,
}: OverviewMonitoringProps) {
  return (
    <>
      <section className="overview-section" aria-labelledby="database-monitoring-heading">
        <div className="section-heading">
          <div>
            <p className="panel-scope">Database-wide</p>
            <h2 id="database-monitoring-heading">Database monitoring</h2>
          </div>
          {database !== undefined && <ObservedAt value={database.observedAt} />}
        </div>
        {databaseProblem !== undefined && <PanelProblem problem={databaseProblem} stale={database !== undefined} />}
        {database === undefined ? (
          <p aria-busy="true">Loading database monitoring…</p>
        ) : (
          <div className="overview-panels overview-panels--wide">
            <MonitoringPanel title="Database storage">
              <AvailableDetail bytes label="Table storage" value={database.tableBytes} />
              <AvailableDetail bytes label="Index storage" value={database.indexBytes} />
              <AvailableDetail bytes label="Total schema storage" value={database.schemaBytes} />
            </MonitoringPanel>
            <MonitoringPanel title="Database rows and maintenance">
              <AvailableDetail label="Live rows" value={database.liveRows} />
              <AvailableDetail label="Expired rows" value={database.expiredRows} />
              <AvailableDetail label="Dead tuples" value={database.deadTuples} />
              <Detail label="Expiry backlog" value={formatDecimal(database.expiryBacklog)} />
              <Detail label="Oldest expired lag" value={database.oldestExpiredRowLagMillis === null ? 'None' : formatDuration(database.oldestExpiredRowLagMillis)} />
              <Detail label="Last vacuum" value={formatOptionalInstant(database.lastVacuumAt)} />
              <Detail label="Last autovacuum" value={formatOptionalInstant(database.lastAutovacuumAt)} />
            </MonitoringPanel>
            <MonitoringPanel title="Database connections">
              <AvailableDetail label="All database connections" value={database.databaseConnections} />
              <AvailableDetail label="PeeGeeQ Cache connections" value={database.cacheConnections} />
            </MonitoringPanel>
          </div>
        )}
      </section>

      <section className="overview-section" aria-labelledby="runtime-monitoring-heading">
        <div className="section-heading">
          <div>
            <p className="panel-scope">Management-server-local</p>
            <h2 id="runtime-monitoring-heading">Management runtime</h2>
          </div>
          {runtime !== undefined && <ObservedAt value={runtime.observedAt} />}
        </div>
        {runtimeProblem !== undefined && <PanelProblem problem={runtimeProblem} stale={runtime !== undefined} />}
        {runtime === undefined ? (
          <p aria-busy="true">Loading management runtime…</p>
        ) : (
          <div className="overview-panels overview-panels--wide">
            <MonitoringPanel title="Runtime state">
              <Detail label="Lifecycle" value={humanize(runtime.lifecycleState)} />
              <Detail label="Active operations" value={formatDecimal(runtime.activeOperations)} />
              <Detail label="Pub/Sub subscriptions" value={formatDecimal(runtime.pubSubSubscriptions)} />
              <Detail label="SSE clients" value={formatDecimal(runtime.sseClients)} />
              <Detail label="WebSocket clients" value={formatDecimal(runtime.webSocketClients)} />
              <Detail label="Retained payload" value={formatBytes(runtime.retainedPayloadBytes)} />
            </MonitoringPanel>
            <MonitoringPanel title="Management pool">
              <AvailableDetail label="Active" value={runtime.pool.active} />
              <AvailableDetail label="Idle" value={runtime.pool.idle} />
              <AvailableDetail label="Pending" value={runtime.pool.pending} />
              <AvailableDetail label="Maximum" value={runtime.pool.maximum} />
            </MonitoringPanel>
            <MonitoringPanel title="Audit and expiry">
              <Detail label="Audit queue" value={`${formatDecimal(runtime.auditQueue.depth)} / ${formatDecimal(runtime.auditQueue.capacity)}`} />
              <Detail label="Accepting mutations" value={runtime.auditQueue.acceptingMutations ? 'Yes' : 'No'} />
              <Detail label="Runtime owns sweeper" value={runtime.expirySweeper.ownedByRuntime ? 'Yes' : 'No'} />
              <Detail label="Sweeper running" value={runtime.expirySweeper.running ? 'Yes' : 'No'} />
              <Detail label="Last sweep" value={formatOptionalInstant(runtime.expirySweeper.lastSweepAt)} />
            </MonitoringPanel>
          </div>
        )}
        {runtime !== undefined && runtime.operations.length > 0 && (
          <div className="table-scroll" tabIndex={0}>
            <table className="data-table">
              <caption>Management-server-local operation aggregates</caption>
              <thead><tr><th>Operation</th><th>Status</th><th>Count</th><th>Errors</th><th>Total latency</th></tr></thead>
              <tbody>{runtime.operations.map((operation) => (
                <tr key={`${operation.operation}:${operation.status}`}>
                  <td>{operation.operation}</td>
                  <td>{humanize(operation.status)}</td>
                  <td>{formatDecimal(operation.count)}</td>
                  <td>{formatDecimal(operation.errorCount)}</td>
                  <td>{formatDuration(BigInt(operation.latencyMillis))}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>

      <section className="overview-section" aria-labelledby="recent-activity-heading">
        <div className="section-heading">
          <div>
            <p className="panel-scope">Management-server-local · bounded · non-authoritative</p>
            <h2 id="recent-activity-heading">Recent activity</h2>
          </div>
          <p className="live-status"><span aria-hidden="true" />Refreshes every 15 seconds</p>
        </div>
        {activityProblem !== undefined && <PanelProblem problem={activityProblem} stale={activity !== undefined} />}
        {activity === undefined ? (
          <p aria-busy="true">Loading recent activity…</p>
        ) : activity.items.length === 0 ? (
          <p>No activity is retained for this setup in the current management process.</p>
        ) : (
          <div className="table-scroll" tabIndex={0}>
            <table className="data-table">
              <thead><tr><th>Occurred</th><th>Actor</th><th>Action</th><th>Outcome</th><th>Resource</th><th>Summary</th><th>Correlation</th></tr></thead>
              <tbody>{activity.items.slice(0, 20).map((event) => (
                <tr key={event.eventId}>
                  <td>{formatDisplayInstant(event.occurredAt)}</td>
                  <td>{event.actor}</td>
                  <td>{humanize(event.action)}</td>
                  <td>{humanize(event.outcome)}</td>
                  <td>{humanize(event.resource.type)}</td>
                  <td>{event.summary || 'No summary'}</td>
                  <td>{event.correlationId}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function MonitoringPanel({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  const id = `monitoring-${title.toLowerCase().replaceAll(/[^a-z0-9]+/gu, '-')}`;
  return <section className="overview-panel" aria-labelledby={id}><h3 id={id}>{title}</h3><dl className="compact-details">{children}</dl></section>;
}

function AvailableDetail({ label, value, bytes = false }: { readonly label: string; readonly value: AvailableLong; readonly bytes?: boolean }) {
  if (value.availability === 'UNAVAILABLE') {
    return <Detail label={label} value="Unavailable" detail={value.reason} />;
  }
  return <Detail label={label} value={bytes ? formatBytes(value.value) : formatDecimal(value.value)} />;
}

function Detail({ label, value, detail }: { readonly label: string; readonly value: string; readonly detail?: string }) {
  return <div><dt>{label}</dt><dd>{value}{detail !== undefined && <small>{detail}</small>}</dd></div>;
}

function ObservedAt({ value }: { readonly value: string }) {
  return <p className="observed-at">Observed <time dateTime={value}>{formatDisplayInstant(value)}</time></p>;
}

function PanelProblem({ problem, stale }: { readonly problem: ManagementClientError; readonly stale: boolean }) {
  return <p className="panel-problem" role="status"><strong>{stale ? 'Stale' : 'Unavailable'} · {problem.code}</strong> {problem.message}</p>;
}

function formatOptionalInstant(value: string | null): string {
  return value === null ? 'Not observed' : formatDisplayInstant(value);
}

function formatDecimal(value: string): string {
  return BigInt(value).toLocaleString('en-US');
}

function formatBytes(value: string): string {
  const bytes = BigInt(value);
  if (bytes < 1_024n) return `${formatDecimal(value)} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
  let divisor = 1_024n;
  let unit = 0;
  while (bytes >= divisor * 1_024n && unit < units.length - 1) {
    divisor *= 1_024n;
    unit += 1;
  }
  const whole = bytes / divisor;
  const tenths = (bytes % divisor) * 10n / divisor;
  return `${whole}${whole < 10n && tenths > 0n ? `.${tenths}` : ''} ${units[unit]}`;
}

function formatDuration(value: number | bigint): string {
  const milliseconds = typeof value === 'bigint' ? value : BigInt(value);
  if (milliseconds < 1_000n) return `${milliseconds} ms`;
  const seconds = milliseconds / 1_000n;
  const tenths = (milliseconds % 1_000n) / 100n;
  return `${seconds}${tenths > 0n ? `.${tenths}` : ''} s`;
}

function humanize(value: string): string {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./u, (letter) => letter.toUpperCase());
}
