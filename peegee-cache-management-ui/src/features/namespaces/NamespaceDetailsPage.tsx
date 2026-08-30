import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { NamespaceClientPort } from '../../api/inspection-client';
import type { NamespaceDetails } from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';

interface NamespaceDetailsPageProps {
  readonly client: NamespaceClientPort;
  readonly encodedNamespace: string;
  readonly selectedSetupId?: string;
  readonly onSelectNamespace: (namespace: string) => void;
}

export function NamespaceDetailsPage({ client, encodedNamespace, selectedSetupId, onSelectNamespace }: NamespaceDetailsPageProps) {
  const [detailsState, setDetailsState] = useState<{
    setupId: string;
    encodedNamespace: string;
    value: NamespaceDetails;
  }>();
  const [problemState, setProblemState] = useState<{
    setupId: string;
    encodedNamespace: string;
    value: ManagementClientError;
  }>();
  const details = detailsState !== undefined && detailsState.setupId === selectedSetupId
      && detailsState.encodedNamespace === encodedNamespace
    ? detailsState.value
    : undefined;
  const problem = problemState !== undefined && problemState.setupId === selectedSetupId
      && problemState.encodedNamespace === encodedNamespace
    ? problemState.value
    : undefined;

  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    let active = true;
    const setupId = selectedSetupId;
    void client.namespace(setupId, encodedNamespace)
      .then((loaded) => {
        if (!active) return;
        setDetailsState({ setupId, encodedNamespace, value: loaded });
        setProblemState(undefined);
        onSelectNamespace(loaded.stats.namespace);
      })
      .catch((failure: unknown) => {
        if (active) setProblemState({ setupId, encodedNamespace, value: failure instanceof ManagementClientError ? failure : new ManagementClientError(0, 'CONNECTION_FAILED', 'Namespace details could not be loaded') });
      });
    return () => { active = false; };
  }, [client, encodedNamespace, onSelectNamespace, selectedSetupId]);

  if (selectedSetupId === undefined) return <DetailsWorkspace title="Namespace details"><div className="empty-state"><h2>Select a connected setup</h2></div></DetailsWorkspace>;
  if (problem !== undefined) return <DetailsWorkspace title="Namespace details"><div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p></div></DetailsWorkspace>;
  if (details === undefined) return <DetailsWorkspace title="Namespace details"><p aria-busy="true">Loading namespace details…</p></DetailsWorkspace>;

  const stats = details.stats;
  return (
    <DetailsWorkspace title={stats.namespace}>
      <p className="scope-label">Database-wide · Observed {formatDisplayInstant(stats.observedAt)}</p>
      <div className="metric-grid" aria-label="Namespace totals">
        <Metric label="Live entries" value={stats.liveEntryCount} /><Metric label="Live counters" value={stats.liveCounterCount} /><Metric label="Active locks" value={stats.activeLockCount} /><Metric label="Expiring entries" value={stats.expiringEntryCount} /><Metric label="Expired entries" value={stats.expiredEntryCount} /><Metric label="Estimated storage" suffix=" B" value={stats.estimatedStorageBytes} />
      </div>
      <div className="tabs" role="tablist" aria-label="Namespace resources">
        <button aria-selected="true" role="tab" type="button">Overview</button>
        <Link aria-selected="false" role="tab" to="/keys">Entries</Link>
        <Link aria-selected="false" role="tab" to={`/counters?namespace=${stats.encodedNamespace}`}>Counters</Link>
        <Link aria-selected="false" role="tab" to={`/locks?namespace=${stats.encodedNamespace}`}>Locks</Link>
      </div>
      <div className="overview-panels"><section className="overview-panel"><h2>Value types</h2><dl className="compact-details">{Object.entries(details.valueTypeCounts).map(([type, count]) => <div key={type}><dt>{type}</dt><dd>{BigInt(count).toLocaleString('en-US')}</dd></div>)}</dl></section><section className="overview-panel"><h2>TTL distribution</h2><dl className="compact-details">{details.ttlDistribution.map((bucket) => <div key={bucket.range}><dt>{humanize(bucket.range)}</dt><dd>{BigInt(bucket.count).toLocaleString('en-US')}</dd></div>)}</dl></section></div>
    </DetailsWorkspace>
  );
}

function DetailsWorkspace({ title, children }: { readonly title: string; readonly children: ReactNode }) { return <section className="workspace" aria-labelledby="namespace-title"><div className="workspace__heading"><div><p className="workspace__context"><Link to="/namespaces">Namespaces</Link></p><h1 id="namespace-title">{title}</h1></div></div>{children}</section>; }
function Metric({ label, value, suffix = '' }: { readonly label: string; readonly value: string; readonly suffix?: string }) { return <article className="metric-card"><p>{label}</p><strong>{BigInt(value).toLocaleString('en-US')}{suffix}</strong></article>; }
function humanize(value: string): string { return value.toLowerCase().replaceAll('_', ' ').replace(/^./u, (letter) => letter.toUpperCase()); }
