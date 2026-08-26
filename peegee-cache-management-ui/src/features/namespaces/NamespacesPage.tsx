import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { NamespaceClientPort, NamespaceQuery } from '../../api/inspection-client';
import type { NamespacePage } from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { formatDisplayInstant } from '../../presentation/display-time';

interface NamespacesPageProps {
  readonly client: NamespaceClientPort;
  readonly selectedSetupId?: string;
}

export function NamespacesPage({ client, selectedSetupId }: NamespacesPageProps) {
  const [draftPrefix, setDraftPrefix] = useState('');
  const [draftStatus, setDraftStatus] = useState<NonNullable<NamespaceQuery['status']>>('ALL');
  const [draftSort, setDraftSort] = useState<NonNullable<NamespaceQuery['sort']>>('namespace:asc');
  const [query, setQuery] = useState<NamespaceQuery>({ status: 'ALL', sort: 'namespace:asc', limit: 50 });
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [page, setPage] = useState<NamespacePage>();
  const [loading, setLoading] = useState(selectedSetupId !== undefined);
  const [problem, setProblem] = useState<ManagementClientError>();
  const [notice, setNotice] = useState<string>();

  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    let active = true;
    void client.namespaces(selectedSetupId, { ...query, cursor })
      .then((loaded) => {
        if (!active) return;
        setPage(loaded);
        setProblem(undefined);
      })
      .catch((failure: unknown) => {
        if (active) setProblem(asClientError(failure));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [client, cursor, query, selectedSetupId]);

  const applyFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    setHistory([]);
    setCursor(undefined);
    setNotice(undefined);
    setQuery({
      prefix: draftPrefix.trim() || undefined,
      status: draftStatus,
      sort: draftSort,
      limit: 50,
    });
  };

  const next = () => {
    if (page?.nextCursor === null || page?.nextCursor === undefined) return;
    setLoading(true);
    setHistory((current) => [...current, cursor ?? null]);
    setCursor(page.nextCursor);
  };

  const previous = () => {
    const prior = history.at(-1);
    if (prior === undefined) return;
    setLoading(true);
    setHistory((current) => current.slice(0, -1));
    setCursor(prior ?? undefined);
  };

  const exportCurrent = async () => {
    if (selectedSetupId === undefined) return;
    setNotice(undefined);
    try {
      const exported = await client.exportNamespaces(selectedSetupId, {
        prefix: query.prefix,
        status: query.status,
        sort: query.sort,
      });
      downloadExport(exported, selectedSetupId);
      setNotice(`Exported ${exported.items.length.toLocaleString('en-US')} namespace${exported.items.length === 1 ? '' : 's'}${exported.truncated ? ' (server limit reached)' : ''}.`);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    }
  };

  if (selectedSetupId === undefined) {
    return <NamespaceWorkspace><div className="empty-state"><h2>Select a connected setup</h2><p>Namespace inspection requires an active database setup.</p></div></NamespaceWorkspace>;
  }

  return (
    <NamespaceWorkspace actions={<button className="button button--secondary" disabled={page === undefined} onClick={() => void exportCurrent()} type="button">Export namespaces</button>}>
      <form className="filter-bar" onSubmit={applyFilters}>
        <label className="field" htmlFor="namespace-prefix">Namespace prefix<input id="namespace-prefix" maxLength={128} onChange={(event) => setDraftPrefix(event.target.value)} value={draftPrefix} /></label>
        <label className="field" htmlFor="namespace-status">Status<select id="namespace-status" onChange={(event) => setDraftStatus(event.target.value as NonNullable<NamespaceQuery['status']>)} value={draftStatus}><option value="ALL">All</option><option value="HEALTHY">Healthy</option><option value="EXPIRED_BACKLOG">Expired backlog</option><option value="ACTIVE_LOCKS">Active locks</option></select></label>
        <label className="field" htmlFor="namespace-sort">Sort<select id="namespace-sort" onChange={(event) => setDraftSort(event.target.value as NonNullable<NamespaceQuery['sort']>)} value={draftSort}><option value="namespace:asc">Namespace</option><option value="entryCount:desc">Live entries</option></select></label>
        <button className="button" disabled={loading} type="submit">Apply filters</button>
      </form>
      {problem !== undefined && <div className="diagnostics" role="alert"><strong>{page === undefined ? problem.code : `Stale data · ${problem.code}`}</strong><p>{problem.message}</p>{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</div>}
      {notice !== undefined && <p className="notice" role="status">{notice}</p>}
      {page === undefined && loading && <p aria-busy="true">Loading namespaces…</p>}
      {page !== undefined && page.items.length === 0 && <div className="empty-state"><h2>No namespaces matched</h2><p>Change the current prefix or status filter.</p></div>}
      {page !== undefined && page.items.length > 0 && (
        <div className="table-scroll"><table className="data-table"><thead><tr><th>Namespace</th><th>Live entries</th><th>Counters</th><th>Locks</th><th>Expiring</th><th>Expired</th><th>Storage</th><th>Observed</th></tr></thead><tbody>{page.items.map((item) => <tr key={item.encodedNamespace}><td><Link to={`/namespaces/${item.encodedNamespace}`}>{item.namespace}</Link></td><td>{formatDecimal(item.liveEntryCount)}</td><td>{formatDecimal(item.liveCounterCount)}</td><td>{formatDecimal(item.activeLockCount)}</td><td>{formatDecimal(item.expiringEntryCount)}</td><td>{formatDecimal(item.expiredEntryCount)}</td><td>{formatBytes(item.estimatedStorageBytes)}</td><td>{formatDisplayInstant(item.observedAt)}</td></tr>)}</tbody></table></div>
      )}
      {page !== undefined && <nav className="pagination" aria-label="Namespace pages"><button className="button button--secondary" disabled={history.length === 0 || loading} onClick={previous} type="button">Previous page</button><span>Page {history.length + 1}</span><button className="button button--secondary" disabled={!page.hasMore || loading} onClick={next} type="button">Next page</button></nav>}
    </NamespaceWorkspace>
  );
}

function NamespaceWorkspace({ actions, children }: { readonly actions?: ReactNode; readonly children: ReactNode }) {
  return <section className="workspace" aria-labelledby="namespaces-title"><div className="workspace__heading"><div><p className="workspace__context">Database-wide inspection</p><h1 id="namespaces-title">Namespaces</h1><p>Cursor-paginated namespace metadata for the selected setup.</p></div>{actions}</div>{children}</section>;
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

function asClientError(failure: unknown): ManagementClientError {
  return failure instanceof ManagementClientError ? failure : new ManagementClientError(0, 'CONNECTION_FAILED', 'Namespace inspection could not be completed');
}

function formatDecimal(value: string): string { return BigInt(value).toLocaleString('en-US'); }
function formatBytes(value: string): string { return `${formatDecimal(value)} B`; }
