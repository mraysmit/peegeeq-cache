import {
  useCallback,
  useEffect,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from 'react';

import type { MonitoringClientPort, OverviewClientPort } from '../../api/inspection-client';
import type {
  ActivityPage,
  DatabaseMonitoring,
  Overview,
  RuntimeMonitoring,
} from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { formatDisplayInstant } from '../../presentation/display-time';
import { loadPreferences } from '../../state/preferences';
import { OverviewMonitoring } from './OverviewMonitoring';
import { SessionTrendChart, type SessionTrendPoint } from './SessionTrendChart';

interface OverviewPageProps {
  readonly client: OverviewClientPort & MonitoringClientPort;
  readonly selectedSetupId?: string;
}

const valueTypes = ['STRING', 'JSON', 'LONG', 'BYTES'] as const;

type KeyedValue<T> = { setupId: string; value: T };
type MonitoringProblems = Partial<Record<'activity' | 'database' | 'runtime', ManagementClientError>>;

export function OverviewPage({ client, selectedSetupId }: OverviewPageProps) {
  const [snapshotState, setSnapshotState] = useState<KeyedValue<Overview>>();
  const [databaseState, setDatabaseState] = useState<KeyedValue<DatabaseMonitoring>>();
  const [runtimeState, setRuntimeState] = useState<KeyedValue<RuntimeMonitoring>>();
  const [activityState, setActivityState] = useState<KeyedValue<ActivityPage>>();
  const [trendState, setTrendState] = useState<{ setupId: string; points: SessionTrendPoint[] }>();
  const [loading, setLoading] = useState(selectedSetupId !== undefined);
  const [problemState, setProblemState] = useState<{ setupId: string; value: ManagementClientError }>();
  const [monitoringProblems, setMonitoringProblems] = useState<MonitoringProblems>({});
  const snapshot = snapshotState !== undefined && snapshotState.setupId === selectedSetupId
    ? snapshotState.value
    : undefined;
  const database = databaseState !== undefined && databaseState.setupId === selectedSetupId
    ? databaseState.value
    : undefined;
  const runtime = runtimeState !== undefined && runtimeState.setupId === selectedSetupId
    ? runtimeState.value
    : undefined;
  const activity = activityState !== undefined && activityState.setupId === selectedSetupId
    ? activityState.value
    : undefined;
  const trend = trendState !== undefined && trendState.setupId === selectedSetupId
    ? trendState.points
    : [];
  const problem = problemState !== undefined && problemState.setupId === selectedSetupId
    ? problemState.value
    : undefined;

  const loadSnapshot = useCallback(async (
    setupId: string,
    isActive: () => boolean,
  ) => {
    const [overviewResult, databaseResult, runtimeResult, activityResult] = await Promise.allSettled([
      client.overview(setupId),
      client.databaseMonitoring(setupId),
      client.runtimeMonitoring(setupId),
      client.activity(setupId, { limit: 20 }),
    ]);
    if (!isActive()) return;

    if (overviewResult.status === 'fulfilled') {
      const loaded = overviewResult.value;
      setSnapshotState({ setupId, value: loaded });
      setProblemState(undefined);
      setTrendState((current) => appendTrend(current, setupId, loaded));
    } else {
      setProblemState({ setupId, value: asClientError(overviewResult.reason) });
    }
    applyMonitoringResult('database', databaseResult, setupId, setDatabaseState, setMonitoringProblems);
    applyMonitoringResult('runtime', runtimeResult, setupId, setRuntimeState, setMonitoringProblems);
    applyMonitoringResult('activity', activityResult, setupId, setActivityState, setMonitoringProblems);
    setLoading(false);
  }, [client]);

  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    let active = true;
    const isActive = () => active;
    const initialTimer = window.setTimeout(() => {
      void loadSnapshot(selectedSetupId, isActive);
    }, 0);
    const timer = window.setInterval(() => {
      void loadSnapshot(selectedSetupId, isActive);
    }, loadPreferences().refreshSeconds * 1_000);
    return () => {
      active = false;
      window.clearTimeout(initialTimer);
      window.clearInterval(timer);
    };
  }, [loadSnapshot, selectedSetupId]);

  const refresh = async () => {
    if (selectedSetupId !== undefined) {
      setLoading(true);
      await loadSnapshot(selectedSetupId, () => true);
    }
  };

  if (selectedSetupId === undefined) {
    return (
      <Workspace title="Overview">
        <div className="empty-state">
          <h2>Select a connected setup</h2>
          <p>Database-wide cache information is available after a setup is selected.</p>
        </div>
      </Workspace>
    );
  }

  if (snapshot === undefined && loading) {
    return <Workspace title="Overview"><p aria-busy="true">Loading database overview…</p></Workspace>;
  }

  if (snapshot === undefined) {
    return (
      <Workspace title="Overview" actions={<RefreshButton loading={loading} onRefresh={refresh} />}>
        {problem !== undefined && <ProblemNotice problem={problem} stale={false} />}
      </Workspace>
    );
  }

  return (
    <Workspace
      title="Overview"
      actions={<RefreshButton loading={loading} onRefresh={refresh} />}
      description={(
        <>
          <strong>Database-wide snapshot</strong>
          {' · Observed '}
          <time aria-label="Snapshot observed at" dateTime={snapshot.observedAt}>
            {formatDisplayInstant(snapshot.observedAt)}
          </time>
        </>
      )}
    >
      {problem !== undefined && <ProblemNotice problem={problem} stale />}
      <p className="scope-label">
        Values are database-wide unless a panel is explicitly labelled management-server-local.
      </p>
      <div className="metric-grid" aria-label="Database overview totals">
        <Metric label="Setup status" value={titleCase(snapshot.health.status)} detail={`${snapshot.health.latencyMillis} ms round trip`} />
        <Metric label="Live cache entries" value={formatDecimal(snapshot.totals.liveEntryCount)} />
        <Metric label="Live counters" value={formatDecimal(snapshot.totals.liveCounterCount)} />
        <Metric label="Active locks" value={formatDecimal(snapshot.totals.activeLockCount)} />
        <Metric label="Expired rows awaiting cleanup" value={formatDecimal(snapshot.totals.expiredEntryCount)} />
        {snapshot.totals.schemaBytes.availability === 'AVAILABLE'
          ? <Metric label="Cache schema storage" value={formatDisplayBytes(snapshot.totals.schemaBytes.value)} />
          : <Metric label="Cache schema storage" value="Unavailable" detail={snapshot.totals.schemaBytes.reason} />}
      </div>

      <div className="overview-panels">
        <section className="overview-panel" aria-labelledby="expiry-heading">
          <h2 id="expiry-heading">Expiry and cleanup</h2>
          <dl className="compact-details">
            <Detail label="Sweeper" value={snapshot.expiry.sweeperEnabled ? 'Enabled' : 'Disabled'} />
            <Detail label="Oldest backlog lag" value={snapshot.expiry.oldestExpiredRowLagMillis === null ? 'None' : formatDuration(snapshot.expiry.oldestExpiredRowLagMillis)} />
            <Detail label="Last sweep deleted" value={formatDecimal(snapshot.expiry.lastSweepDeletedRows)} />
            <Detail label="Last sweep" value={snapshot.expiry.lastSweepAt === null ? 'Not observed' : formatDisplayInstant(snapshot.expiry.lastSweepAt)} />
          </dl>
        </section>
        <section className="overview-panel" aria-labelledby="types-heading">
          <h2 id="types-heading">Entry value types</h2>
          <dl className="compact-details">
            {valueTypes.map((type) => (
              <Detail key={type} label={titleCase(type)} value={snapshot.valueTypeCounts[type] === undefined ? 'Not reported' : formatDecimal(snapshot.valueTypeCounts[type])} />
            ))}
          </dl>
        </section>
      </div>

      <SessionTrendChart points={trend} />

      <OverviewMonitoring
        activity={activity}
        activityProblem={monitoringProblems.activity}
        database={database}
        databaseProblem={monitoringProblems.database}
        runtime={runtime}
        runtimeProblem={monitoringProblems.runtime}
      />

      <section className="overview-section" aria-labelledby="namespace-overview-heading">
        <h2 id="namespace-overview-heading">Namespace overview</h2>
        {snapshot.topNamespaces.length === 0 ? (
          <p>No namespaces were observed in this database snapshot.</p>
        ) : (
          <div aria-label="Namespace overview results" className="table-scroll" role="region" tabIndex={0}>
            <table className="data-table">
              <thead><tr><th>Namespace</th><th>Live entries</th><th>Counters</th><th>Locks</th><th>Expired</th><th>Storage</th></tr></thead>
              <tbody>
                {snapshot.topNamespaces.map((namespace) => (
                  <tr key={namespace.encodedNamespace}>
                    <td><strong>{namespace.namespace}</strong><small>Observed {formatDisplayInstant(namespace.observedAt)}</small></td>
                    <td>{formatDecimal(namespace.liveEntryCount)}</td>
                    <td>{formatDecimal(namespace.liveCounterCount)}</td>
                    <td>{formatDecimal(namespace.activeLockCount)}</td>
                    <td>{formatDecimal(namespace.expiredEntryCount)}</td>
                    <td>{formatDisplayBytes(namespace.estimatedStorageBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
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
    <section className="workspace" aria-labelledby="overview-title">
      <div className="workspace__heading">
        <div><p className="workspace__context">Selected setup inspection</p><h1 id="overview-title">{title}</h1>{description !== undefined && <p>{description}</p>}</div>
        {actions}
      </div>
      {children}
    </section>
  );
}

function RefreshButton({ loading, onRefresh }: { readonly loading: boolean; readonly onRefresh: () => Promise<void> }) {
  return <button className="button button--secondary" disabled={loading} onClick={() => void onRefresh()} type="button">{loading ? 'Refreshing…' : 'Refresh overview'}</button>;
}

function Metric({ label, value, detail }: { readonly label: string; readonly value: string; readonly detail?: string }) {
  return <article className="metric-card"><p>{label}</p><strong>{value}</strong>{detail !== undefined && <small>{detail}</small>}</article>;
}

function Detail({ label, value }: { readonly label: string; readonly value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}

function ProblemNotice({ problem, stale }: { readonly problem: ManagementClientError; readonly stale: boolean }) {
  return <div className="diagnostics" role="alert"><strong>{stale ? `Stale data · ${problem.code}` : problem.code}</strong><p>{problem.message}</p>{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</div>;
}

function asClientError(failure: unknown): ManagementClientError {
  return failure instanceof ManagementClientError
    ? failure
    : new ManagementClientError(0, 'CONNECTION_FAILED', 'The database overview could not be loaded');
}

function formatDecimal(value: string): string {
  return BigInt(value).toLocaleString('en-US');
}

function formatDuration(milliseconds: number): string {
  if (milliseconds < 1_000) return `${milliseconds} ms`;
  return `${(milliseconds / 1_000).toFixed(milliseconds % 1_000 === 0 ? 0 : 1)} s`;
}

function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ');
}

function appendTrend(
  current: { setupId: string; points: SessionTrendPoint[] } | undefined,
  setupId: string,
  snapshot: Overview,
): { setupId: string; points: SessionTrendPoint[] } {
  const point: SessionTrendPoint = {
    observedAt: snapshot.observedAt,
    liveEntries: snapshot.totals.liveEntryCount,
    expiredEntries: snapshot.totals.expiredEntryCount,
  };
  const points = current?.setupId === setupId ? current.points : [];
  const withoutSameInstant = points.filter((existing) => existing.observedAt !== point.observedAt);
  return { setupId, points: [...withoutSameInstant, point].slice(-30) };
}

function applyMonitoringResult<T>(
  slot: keyof MonitoringProblems,
  result: PromiseSettledResult<T>,
  setupId: string,
  setValue: (value: KeyedValue<T>) => void,
  setProblems: Dispatch<SetStateAction<MonitoringProblems>>,
): void {
  if (result.status === 'fulfilled') {
    setValue({ setupId, value: result.value });
    setProblems((current) => ({ ...current, [slot]: undefined }));
  } else {
    setProblems((current) => ({ ...current, [slot]: asClientError(result.reason) }));
  }
}
