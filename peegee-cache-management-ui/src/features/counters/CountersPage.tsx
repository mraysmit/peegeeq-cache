import { useEffect, useState, type ReactNode } from 'react';

import { encodeKey, encodeNamespace } from '../../api/identifier-codec';
import type { CounterClientPort } from '../../api/resource-client';
import type { Counter, CounterPage } from '../../api/resource-schemas';
import type { BulkDeletePreview } from '../../api/entry-administration-schemas';
import { ManagementClientError } from '../../api/session-client';
import { Modal } from '../../components/Modal';
import { formatDisplayInstant } from '../../presentation/display-time';

export function CountersPage({ canOperate, canBulkDelete = canOperate, client, selectedSetupId }: { readonly canOperate: boolean; readonly canBulkDelete?: boolean; readonly client: CounterClientPort; readonly selectedSetupId?: string }) {
  const [page, setPage] = useState<CounterPage>();
  const [problem, setProblem] = useState<ManagementClientError>();
  const [prefix, setPrefix] = useState('');
  const [namespace, setNamespace] = useState('');
  const [active, setActive] = useState<Counter>();
  const [creating, setCreating] = useState(false);
  const [createNamespace, setCreateNamespace] = useState('');
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [delta, setDelta] = useState('');
  const [ttl, setTtl] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<BulkDeletePreview>();
  const [confirmation, setConfirmation] = useState('');
  const [reload, setReload] = useState(0);
  const [deleteConfirmation, setDeleteConfirmation] = useState('');

  useEffect(() => {
    if (selectedSetupId === undefined) return;
    let mounted = true;
    void client.counters(selectedSetupId, { namespace: namespace.trim() || undefined, prefix: prefix.trim() || undefined, ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50 })
      .then((loaded) => { if (mounted) { setPage(loaded); setProblem(undefined); setSelected(new Set()); } })
      .catch((failure: unknown) => { if (mounted) setProblem(asError(failure)); });
    return () => { mounted = false; };
  }, [client, namespace, prefix, reload, selectedSetupId]);

  const commit = async (operation: () => Promise<Counter>, message: string) => {
    setBusy(true); setProblem(undefined); setStatus('');
    try {
      const updated = await operation();
      setActive(updated);
      setPage((current) => current === undefined ? current : upsertCounter(current, updated, namespace, prefix));
      setStatus(`${message}: ${formatSigned(updated.value)} · version ${updated.version}`);
    }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };

  const setExact = () => {
    if (selectedSetupId === undefined) return;
    const currentNamespace = active?.namespace ?? createNamespace;
    const currentKey = active?.key ?? key;
    void commit(() => client.setCounter(selectedSetupId, encodeNamespace(currentNamespace), encodeKey(currentKey), active?.version, { value, ttlMode: ttl === '' ? 'PRESERVE_EXISTING' : 'REPLACE', ttlMillis: ttl === '' ? null : positive(ttl) }), active === undefined ? 'Counter created' : 'Counter set');
  };

  const adjust = () => {
    if (selectedSetupId === undefined) return;
    const createIfMissing = active === undefined;
    const currentNamespace = active?.namespace ?? createNamespace;
    const currentKey = active?.key ?? key;
    const ttlMode = createIfMissing ? (ttl === '' ? 'REMOVE' : 'REPLACE') : 'PRESERVE_EXISTING';
    void commit(
      () => client.adjustCounter(
        selectedSetupId,
        active?.encodedNamespace ?? encodeNamespace(currentNamespace),
        active?.encodedKey ?? encodeKey(currentKey),
        active?.version,
        {
          delta,
          createIfMissing,
          ttlMode,
          ttlMillis: ttlMode === 'REPLACE' ? positive(ttl) : null,
        },
      ),
      createIfMissing ? 'Counter created by adjustment' : 'Counter adjusted',
    );
  };
  const setCounterTtl = () => { if (selectedSetupId !== undefined && active !== undefined) void commit(() => client.expireCounter(selectedSetupId, active.encodedNamespace, active.encodedKey, active.version, positive(ttl)), 'Counter TTL set'); };
  const persist = () => { if (selectedSetupId !== undefined && active !== undefined) void commit(() => client.persistCounter(selectedSetupId, active.encodedNamespace, active.encodedKey, active.version), 'Counter persisted'); };
  const deleteActive = async () => {
    if (selectedSetupId === undefined || active === undefined || deleteConfirmation !== active.key) return;
    setBusy(true); setProblem(undefined);
    try { await client.deleteCounter(selectedSetupId, active.encodedNamespace, active.encodedKey, active.version); setPage((current) => current === undefined ? current : { ...current, items: current.items.filter((item) => !same(item, active)) }); setStatus(`Counter ${active.key} deleted at observed version ${active.version}.`); setActive(undefined); setCreating(false); setDeleteConfirmation(''); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };
  const loadMore = async () => {
    if (selectedSetupId === undefined || page?.nextCursor === null || page?.nextCursor === undefined) return;
    setBusy(true);
    try { const next = await client.counters(selectedSetupId, { namespace: namespace.trim() || undefined, prefix: prefix.trim() || undefined, ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50, cursor: page.nextCursor }); setPage({ ...next, items: [...page.items, ...next.items] }); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };

  const previewDelete = async () => {
    if (selectedSetupId === undefined || page === undefined) return;
    setBusy(true);
    try { setPreview(await client.previewCounterBulkDelete(selectedSetupId, { targets: page.items.filter((item) => selected.has(id(item))).map((item) => ({ namespace: item.namespace, key: item.key, version: item.version })) })); setConfirmation(''); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };
  const executeDelete = async () => {
    if (selectedSetupId === undefined || preview === undefined || confirmation !== preview.confirmationPhrase) return;
    setBusy(true);
    try { const result = await client.executeCounterBulkDelete(selectedSetupId, { previewToken: preview.previewToken, confirmationPhrase: confirmation }); setStatus(`Deleted ${result.deletedCount} of ${result.processedCount} counters.`); setPreview(undefined); setReload((current) => current + 1); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };

  if (selectedSetupId === undefined) return <Workspace><Empty>Select a connected setup before inspecting counters.</Empty></Workspace>;
  return <Workspace actions={canOperate ? <button className="button" onClick={() => { setCreating(true); setActive(undefined); setCreateNamespace(namespace); setKey(''); setValue(''); }} type="button">Create counter</button> : undefined}>
    <div className="filter-bar"><label className="field" htmlFor="counter-namespace">Namespace<input id="counter-namespace" onChange={(event) => setNamespace(event.target.value)} value={namespace} /></label><label className="field" htmlFor="counter-prefix">Key prefix<input id="counter-prefix" onChange={(event) => setPrefix(event.target.value)} value={prefix} /></label></div>
    {problem !== undefined && <Problem problem={problem} />}{status !== '' && <p role="status">{status}</p>}
    {canBulkDelete && <button className="button button--danger" disabled={selected.size === 0 || busy} onClick={() => void previewDelete()} type="button">Preview selected counter deletion</button>}
    {page === undefined ? <p aria-busy="true">Loading counters…</p> : page.items.length === 0 ? <Empty>No counters matched.</Empty> : <><div aria-label="Counters results" className="table-scroll" role="region" tabIndex={0}><table className="data-table"><thead><tr>{canBulkDelete && <th>Selection</th>}<th>Namespace</th><th>Key</th><th>Value</th><th>Version</th><th>Updated</th><th>TTL</th>{canOperate && <th>Actions</th>}</tr></thead><tbody>{page.items.map((item) => <tr key={id(item)}>{canBulkDelete && <td><input aria-label={`Select ${item.namespace}/${item.key}`} checked={selected.has(id(item))} onChange={() => setSelected(toggle(selected, id(item)))} type="checkbox" /></td>}<td>{item.namespace}</td><td>{item.key}</td><td>{formatSigned(item.value)}</td><td>{item.version}</td><td>{formatDisplayInstant(item.updatedAt)}</td><td>{item.ttl.ttlMillis === null ? 'Persistent' : `${item.ttl.ttlMillis} ms`}</td>{canOperate && <td><button className="button button--quiet" onClick={() => { setActive(item); setCreating(false); setValue(item.value); setDelta(''); setTtl(''); setDeleteConfirmation(''); }} type="button">Manage {item.key}</button></td>}</tr>)}</tbody></table></div>{page.hasMore && <button className="button button--secondary" disabled={busy} onClick={() => void loadMore()} type="button">Load more counters</button>}</>}
    {(active !== undefined || creating) && <Modal labelId="counter-editor-title" onDismiss={() => { setActive(undefined); setCreating(false); }}><h2 id="counter-editor-title">{active === undefined ? 'Create counter' : `Manage ${active.key}`}</h2>{active === undefined && <><label className="field" htmlFor="counter-create-namespace">Namespace<input id="counter-create-namespace" onChange={(event) => setCreateNamespace(event.target.value)} required value={createNamespace} /></label><label className="field" htmlFor="counter-create-key">Key<input id="counter-create-key" onChange={(event) => setKey(event.target.value)} required value={key} /></label></>}<label className="field" htmlFor="counter-exact-value">Exact decimal value<input id="counter-exact-value" onChange={(event) => setValue(event.target.value)} value={value} /></label>{active === undefined && <label className="field" htmlFor="counter-create-ttl">TTL milliseconds (blank for persistent)<input id="counter-create-ttl" min="1" onChange={(event) => setTtl(event.target.value)} type="number" value={ttl} /></label>}<button className="button" disabled={busy || value === ''} onClick={setExact} type="button">Set exact value</button><label className="field" htmlFor="counter-adjustment">Signed adjustment<input id="counter-adjustment" onChange={(event) => setDelta(event.target.value)} value={delta} /></label><button className="button" disabled={busy || delta === '' || delta === '0'} onClick={adjust} type="button">{active === undefined ? 'Create by adjustment' : 'Apply adjustment'}</button>{active !== undefined && <><label className="field" htmlFor="counter-ttl">TTL milliseconds<input id="counter-ttl" min="1" onChange={(event) => setTtl(event.target.value)} type="number" value={ttl} /></label><div className="workspace__actions"><button className="button" disabled={busy || ttl === ''} onClick={setCounterTtl} type="button">Set counter TTL</button><button className="button button--secondary" disabled={busy} onClick={persist} type="button">Make counter persistent</button></div><label className="field" htmlFor="counter-delete-confirm">Confirm counter key<input id="counter-delete-confirm" onChange={(event) => setDeleteConfirmation(event.target.value)} value={deleteConfirmation} /></label><button className="button button--danger" disabled={busy || deleteConfirmation !== active.key} onClick={() => void deleteActive()} type="button">Delete current version</button></>}<div className="modal__actions"><button className="button button--secondary" onClick={() => { setActive(undefined); setCreating(false); }} type="button">Close</button></div></Modal>}
    {preview !== undefined && <Modal labelId="counter-bulk-title" onDismiss={() => setPreview(undefined)}><h2 id="counter-bulk-title">Confirm counter deletion</h2><p>{preview.resolvedCount} counters · required phrase <strong>{preview.confirmationPhrase}</strong></p><label className="field" htmlFor="counter-bulk-confirm">Type confirmation phrase<input id="counter-bulk-confirm" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} /></label><div className="modal__actions"><button className="button button--secondary" onClick={() => setPreview(undefined)} type="button">Cancel</button><button className="button button--danger" disabled={busy || confirmation !== preview.confirmationPhrase} onClick={() => void executeDelete()} type="button">Delete previewed counters</button></div></Modal>}
  </Workspace>;
}

function Workspace({ actions, children }: { actions?: ReactNode; children: ReactNode }) { return <section className="workspace" aria-labelledby="counters-title"><div className="workspace__heading"><div><p className="workspace__context">Exact signed 64-bit administration</p><h1 id="counters-title">Counters</h1></div>{actions}</div>{children}</section>; }
function Empty({ children }: { children: ReactNode }) { return <div className="empty-state"><h2>Counter data unavailable</h2><p>{children}</p></div>; }
function Problem({ problem }: { problem: ManagementClientError }) { return <div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p></div>; }
function asError(value: unknown) { return value instanceof ManagementClientError ? value : new ManagementClientError(0, 'CONNECTION_FAILED', 'Counter operation failed'); }
function id(item: Counter) { return `${item.encodedNamespace}:${item.encodedKey}`; }
function same(a: Counter, b: Counter) { return id(a) === id(b); }
function upsertCounter(page: CounterPage, updated: Counter, namespace: string, prefix: string): CounterPage {
  if ((namespace.trim() !== '' && updated.namespace !== namespace.trim())
      || (prefix.trim() !== '' && !updated.key.startsWith(prefix.trim()))) return page;
  const items = page.items.some((item) => same(item, updated))
    ? page.items.map((item) => same(item, updated) ? updated : item)
    : [...page.items, updated];
  items.sort((left, right) => left.namespace.localeCompare(right.namespace) || left.key.localeCompare(right.key));
  return { ...page, items };
}
function toggle(current: Set<string>, value: string) { const next = new Set(current); if (next.has(value)) next.delete(value); else next.add(value); return next; }
function positive(value: string) { const number = Number(value); if (!Number.isSafeInteger(number) || number < 1) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'TTL must be positive'); return number; }
function formatSigned(value: string) { return BigInt(value).toLocaleString('en-US'); }
