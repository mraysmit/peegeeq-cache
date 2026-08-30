import { useCallback, useEffect, useMemo, useState } from 'react';
import type { MonitoringClientPort } from '../../api/inspection-client';
import type { ActivityPage, DatabaseMonitoring, RuntimeMonitoring } from '../../api/inspection-schemas';
import { MetricsSseTransport, type MetricsStreamPort } from '../../api/live-transport';
import { ManagementClientError } from '../../api/session-client';
import { OverviewMonitoring } from '../overview/OverviewMonitoring';

export function MonitoringPage({ client, metrics, selectedSetupId }: { readonly client: MonitoringClientPort; readonly metrics?: MetricsStreamPort; readonly selectedSetupId?: string }) {
  const [database, setDatabase] = useState<DatabaseMonitoring>(); const [runtime, setRuntime] = useState<RuntimeMonitoring>(); const [activity, setActivity] = useState<ActivityPage>();
  const [problems, setProblems] = useState<Partial<Record<'database' | 'runtime' | 'activity', ManagementClientError>>>({}); const [busy, setBusy] = useState(false);
  const [liveState, setLiveState] = useState<'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED'>('STOPPED');
  const [liveMessage, setLiveMessage] = useState('');
  const metricsTransport = useMemo(() => metrics ?? new MetricsSseTransport(), [metrics]);
  const refresh = useCallback(async () => {
    if (selectedSetupId === undefined) return; setBusy(true);
    const results = await Promise.allSettled([client.databaseMonitoring(selectedSetupId), client.runtimeMonitoring(selectedSetupId), client.activity(selectedSetupId, { limit: 100 })]);
    const next: typeof problems = {};
    if (results[0].status === 'fulfilled') setDatabase(results[0].value); else next.database = error(results[0].reason);
    if (results[1].status === 'fulfilled') setRuntime(results[1].value); else next.runtime = error(results[1].reason);
    if (results[2].status === 'fulfilled') setActivity(results[2].value); else next.activity = error(results[2].reason);
    setProblems(next); setBusy(false);
  }, [client, selectedSetupId]);
  useEffect(() => { const timer = window.setTimeout(() => void refresh(), 0); return () => window.clearTimeout(timer); }, [refresh]);
  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    const stream = metricsTransport.connect(`/api/v1/setups/${encodeURIComponent(selectedSetupId)}/sse/metrics`, {
      onOverview: () => { void refresh(); },
      onRuntime: (snapshot) => { setRuntime(snapshot); setProblems((current) => { const next = { ...current }; delete next.runtime; return next; }); },
      onState: setLiveState,
      onReset: (reason) => { setLiveMessage(reason); void refresh(); },
      onError: setLiveMessage,
    });
    return () => stream.stop();
  }, [metricsTransport, refresh, selectedSetupId]);
  if (selectedSetupId === undefined) return <section className="workspace" aria-labelledby="monitoring-title"><p className="workspace__context">Database-wide and management-server-local telemetry</p><h1 id="monitoring-title">Monitoring</h1><p>Select a connected setup before monitoring it.</p></section>;
  return <section className="workspace" aria-labelledby="monitoring-title"><p className="workspace__context">Database-wide and management-server-local telemetry</p><div className="section-heading"><div><h1 id="monitoring-title">Monitoring</h1><p aria-live="polite" className="live-status">{liveLabel(liveState)}</p></div><button className="button button--secondary" disabled={busy} onClick={() => void refresh()} type="button">{busy ? 'Refreshing…' : 'Refresh monitoring'}</button></div>{liveMessage !== '' && <div className="diagnostics" role="status"><strong>Live transport notice</strong><p>{liveMessage}</p></div>}<OverviewMonitoring activity={activity} activityProblem={problems.activity} database={database} databaseProblem={problems.database} runtime={runtime} runtimeProblem={problems.runtime} /></section>;
}
function error(value: unknown) { return value instanceof ManagementClientError ? value : new ManagementClientError(0, 'CONNECTION_FAILED', 'Monitoring data could not be refreshed'); }
function liveLabel(state: 'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED') { switch (state) { case 'CONNECTED': return 'Live metrics connected'; case 'CONNECTING': return 'Connecting live metrics…'; case 'STALE': return 'Live metrics interrupted; displayed values may be stale'; case 'STOPPED': return 'Live metrics stopped'; } }
