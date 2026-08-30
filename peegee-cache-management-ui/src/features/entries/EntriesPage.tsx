import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { EntryAdministrationClientPort } from '../../api/entry-administration-client';
import type { BulkDeletePreview, EntrySetBody } from '../../api/entry-administration-schemas';
import { encodeKey, encodeNamespace } from '../../api/identifier-codec';
import type { EntryClientPort, EntryQuery } from '../../api/inspection-client';
import type { CacheValue, EntryPage } from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { Modal } from '../../components/Modal';
import { formatDisplayInstant } from '../../presentation/display-time';

interface EntriesPageProps {
  readonly administrationClient?: EntryAdministrationClientPort;
  readonly canOperate?: boolean;
  readonly client: EntryClientPort;
  readonly selectedNamespace?: string;
  readonly selectedSetupId?: string;
}

export function EntriesPage({ administrationClient, canOperate = false, client, selectedNamespace, selectedSetupId }: EntriesPageProps) {
  const [draftPrefix, setDraftPrefix] = useState('');
  const [draftValueType, setDraftValueType] = useState<'ALL' | NonNullable<EntryQuery['valueType']>>('ALL');
  const [draftTtlState, setDraftTtlState] = useState<NonNullable<EntryQuery['ttlState']>>('ALL_LIVE');
  const [query, setQuery] = useState<EntryQuery>({ ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50 });
  const [cursor, setCursor] = useState<string>();
  const [history, setHistory] = useState<Array<string | null>>([]);
  const [page, setPage] = useState<EntryPage>();
  const [loading, setLoading] = useState(selectedSetupId !== undefined && selectedNamespace !== undefined);
  const [problem, setProblem] = useState<ManagementClientError>();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<BulkDeletePreview>();
  const [confirmation, setConfirmation] = useState('');
  const [operationStatus, setOperationStatus] = useState('');
  const [mutating, setMutating] = useState(false);
  const [reload, setReload] = useState(0);
  const [now, setNow] = useState(0);
  const [creating, setCreating] = useState(false);
  const [newKey, setNewKey] = useState('');
  const [newType, setNewType] = useState<EntrySetBody['value']['type']>('STRING');
  const [newValue, setNewValue] = useState('');
  const [newTtl, setNewTtl] = useState('');

  useEffect(() => {
    if (selectedSetupId === undefined || selectedNamespace === undefined) return undefined;
    let active = true;
    const encodedNamespace = encodeNamespace(selectedNamespace);
    void client.entries(selectedSetupId, encodedNamespace, { ...query, cursor })
      .then((loaded) => {
        if (!active) return;
        setPage(loaded);
        setProblem(undefined);
        setSelected(new Set());
      })
      .catch((failure: unknown) => { if (active) setProblem(asClientError(failure)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, cursor, query, reload, selectedNamespace, selectedSetupId]);

  useEffect(() => {
    if (preview === undefined) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [preview]);

  const applyFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
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

  const previewDeletion = async (filterScope: boolean) => {
    if (administrationClient === undefined || selectedSetupId === undefined || selectedNamespace === undefined || page === undefined) return;
    setMutating(true);
    setProblem(undefined);
    setOperationStatus('');
    try {
      const selection = filterScope ? {
        selection: {
          type: 'FILTER' as const,
          prefix: query.prefix,
          valueType: query.valueType,
          ttlState: query.ttlState ?? 'ALL_LIVE',
        },
      } : {
        selection: {
          type: 'EXPLICIT' as const,
          targets: page.items.filter((item) => selected.has(item.encodedKey)).map((item) => ({ key: item.key, version: item.version })),
        },
      };
      const loaded = await administrationClient.previewBulkDelete(selectedSetupId, encodeNamespace(selectedNamespace), selection);
      setPreview(loaded);
      setConfirmation('');
      setNow(Date.now());
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setMutating(false);
    }
  };

  const executeDeletion = async () => {
    if (administrationClient === undefined || selectedSetupId === undefined || selectedNamespace === undefined || preview === undefined) return;
    if (previewExpired(preview, now) || confirmation !== preview.confirmationPhrase) return;
    setMutating(true);
    setProblem(undefined);
    try {
      const result = await administrationClient.executeBulkDelete(selectedSetupId, encodeNamespace(selectedNamespace), {
        previewToken: preview.previewToken,
        confirmationPhrase: confirmation,
      });
      setOperationStatus(`Deleted ${result.deletedCount} of ${result.processedCount} previewed entries · conflicts ${result.conflictCount} · missing ${result.missingCount} · failed ${result.failedCount}.`);
      setPreview(undefined);
      setConfirmation('');
      setSelected(new Set());
      setReload((value) => value + 1);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setMutating(false);
    }
  };

  const createEntry = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (administrationClient === undefined || selectedSetupId === undefined || selectedNamespace === undefined) return;
    setMutating(true);
    setProblem(undefined);
    try {
      const result = await administrationClient.setEntry(selectedSetupId, encodeNamespace(selectedNamespace), encodeKey(newKey), {
        value: valueFor(newType, newValue),
        ttlMode: newTtl === '' ? 'REMOVE' : 'REPLACE',
        ttlMillis: newTtl === '' ? null : positiveInteger(newTtl),
        setMode: 'ONLY_IF_ABSENT',
      });
      setOperationStatus(`Entry created at ${formatDisplayInstant(result.updatedAt)} · version ${result.version}.`);
      setCreating(false);
      setNewKey('');
      setNewValue('');
      setNewTtl('');
      setReload((value) => value + 1);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setMutating(false);
    }
  };

  if (selectedSetupId === undefined) return <EntriesWorkspace><Empty title="Select a connected setup">Entry inspection requires an active database setup.</Empty></EntriesWorkspace>;
  if (selectedNamespace === undefined) return <EntriesWorkspace><Empty title="Select a namespace">Choose a namespace before browsing cache entries.</Empty></EntriesWorkspace>;

  return <EntriesWorkspace namespace={selectedNamespace} actions={canOperate && administrationClient !== undefined ? <button className="button" onClick={() => setCreating(true)} type="button">Create entry</button> : undefined}>
    <form className="filter-bar filter-bar--entries" onSubmit={applyFilters}>
      <label className="field" htmlFor="entry-prefix">Key prefix<input id="entry-prefix" maxLength={1024} onChange={(event) => setDraftPrefix(event.target.value)} value={draftPrefix} /></label>
      <label className="field" htmlFor="entry-value-type">Value type<select id="entry-value-type" onChange={(event) => setDraftValueType(event.target.value as typeof draftValueType)} value={draftValueType}><option value="ALL">All</option><option value="STRING">String</option><option value="JSON">JSON</option><option value="LONG">Long</option><option value="BYTES">Bytes</option></select></label>
      <label className="field" htmlFor="entry-ttl-state">TTL state<select id="entry-ttl-state" onChange={(event) => setDraftTtlState(event.target.value as NonNullable<EntryQuery['ttlState']>)} value={draftTtlState}><option value="ALL_LIVE">All live</option><option value="PERSISTENT">Persistent</option><option value="EXPIRING">Expiring</option><option value="INCLUDE_EXPIRED">Include expired</option></select></label>
      <button className="button" disabled={loading} type="submit">Apply filters</button>
    </form>
    {canOperate && administrationClient !== undefined && <div className="workspace__actions"><button className="button button--danger" disabled={selected.size === 0 || mutating} onClick={() => void previewDeletion(false)} type="button">Preview selected deletion</button><button className="button button--secondary" disabled={mutating} onClick={() => void previewDeletion(true)} type="button">Preview matching-filter deletion</button></div>}
    {operationStatus !== '' && <p role="status">{operationStatus}</p>}
    {problem !== undefined && <Problem problem={problem} stale={page !== undefined} />}
    {page === undefined && loading && <p aria-busy="true">Loading entries…</p>}
    {page !== undefined && page.items.length === 0 && <Empty title="No entries matched">Change the current key, value-type, or TTL filter.</Empty>}
    {page !== undefined && page.items.length > 0 && <EntryTable canSelect={canOperate && administrationClient !== undefined} page={page} selected={selected} setSelected={setSelected} />}
    {page !== undefined && <nav className="pagination" aria-label="Entry pages"><button className="button button--secondary" disabled={history.length === 0 || loading} onClick={previous} type="button">Previous page</button><span>Page {history.length + 1}</span><button className="button button--secondary" disabled={!page.hasMore || loading} onClick={next} type="button">Next page</button></nav>}
    {preview !== undefined && <BulkDialog confirmation={confirmation} execute={executeDeletion} expired={previewExpired(preview, now)} mutating={mutating} preview={preview} setConfirmation={setConfirmation} setPreview={setPreview} />}
    {creating && <CreateDialog createEntry={createEntry} mutating={mutating} newKey={newKey} newTtl={newTtl} newType={newType} newValue={newValue} setCreating={setCreating} setNewKey={setNewKey} setNewTtl={setNewTtl} setNewType={setNewType} setNewValue={setNewValue} />}
  </EntriesWorkspace>;
}

function EntryTable({ canSelect, page, selected, setSelected }: { canSelect: boolean; page: EntryPage; selected: Set<string>; setSelected: (value: Set<string>) => void }) {
  const toggle = (encodedKey: string) => { const next = new Set(selected); if (next.has(encodedKey)) next.delete(encodedKey); else next.add(encodedKey); setSelected(next); };
  return <div className="table-scroll"><table className="data-table"><thead><tr>{canSelect && <th>Selection</th>}<th>Key</th><th>Type</th><th>Size</th><th>Version</th><th>Created</th><th>Updated</th><th>Expires</th><th>Remaining TTL</th><th>Status</th></tr></thead><tbody>{page.items.map((item) => <tr data-selected={selected.has(item.encodedKey) || undefined} key={item.encodedKey}>{canSelect && <td><input aria-label={`Select ${item.key}`} checked={selected.has(item.encodedKey)} onChange={() => toggle(item.encodedKey)} type="checkbox" /></td>}<td><Link to={`/keys/${item.encodedNamespace}/${item.encodedKey}`}>{item.key}</Link></td><td>{item.valueType}</td><td>{formatBytes(item.sizeBytes)}</td><td>{formatDecimal(item.version)}</td><td>{formatDisplayInstant(item.createdAt)}</td><td>{formatDisplayInstant(item.updatedAt)}</td><td>{item.ttl.expiresAt === null ? 'Never' : formatDisplayInstant(item.ttl.expiresAt)}</td><td>{formatTtl(item.ttl.ttlMillis)}</td><td><span className={`badge badge--${item.ttl.state === 'EXPIRED' ? 'negative' : item.ttl.state === 'EXPIRING' ? 'warning' : 'positive'}`}>{humanize(item.ttl.state)}</span></td></tr>)}</tbody></table></div>;
}

function BulkDialog({ confirmation, execute, expired, mutating, preview, setConfirmation, setPreview }: { confirmation: string; execute: () => Promise<void>; expired: boolean; mutating: boolean; preview: BulkDeletePreview; setConfirmation: (value: string) => void; setPreview: (value?: BulkDeletePreview) => void }) { const close = () => setPreview(undefined); return <Modal labelId="bulk-entry-title" onDismiss={close}><h2 id="bulk-entry-title">Confirm bulk entry deletion</h2><p>Setup <strong>{preview.setupId}</strong> · namespace <strong>{preview.namespace}</strong></p><p>Resolved {preview.resolvedCount} entries ({formatBytes(preview.totalBytes)}). Preview expires at {formatDisplayInstant(preview.expiresAt)}.</p>{preview.sampleKeys.length > 0 && <p>Sample: {preview.sampleKeys.join(', ')}</p>}<p>Required phrase: <strong>{preview.confirmationPhrase}</strong></p>{expired && <div className="diagnostics" role="alert"><strong>Preview expired</strong><p>Close this dialog and request a new preview.</p></div>}<label className="field" htmlFor="bulk-entry-confirmation">Type confirmation phrase<input autoComplete="off" id="bulk-entry-confirmation" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} /></label><div className="modal__actions"><button className="button button--secondary" onClick={close} type="button">Cancel</button><button className="button button--danger" disabled={expired || mutating || confirmation !== preview.confirmationPhrase} onClick={() => void execute()} type="button">Delete previewed entries</button></div></Modal>; }

function CreateDialog({ createEntry, mutating, newKey, newTtl, newType, newValue, setCreating, setNewKey, setNewTtl, setNewType, setNewValue }: { createEntry: (event: FormEvent<HTMLFormElement>) => Promise<void>; mutating: boolean; newKey: string; newTtl: string; newType: EntrySetBody['value']['type']; newValue: string; setCreating: (value: boolean) => void; setNewKey: (value: string) => void; setNewTtl: (value: string) => void; setNewType: (value: EntrySetBody['value']['type']) => void; setNewValue: (value: string) => void }) { const close = () => setCreating(false); return <Modal labelId="create-entry-title" onDismiss={close}><h2 id="create-entry-title">Create entry</h2><p>The key must be absent when PostgreSQL commits this request.</p><form className="form-grid" onSubmit={(event) => void createEntry(event)}><label className="field field--wide" htmlFor="new-entry-key">Key<input id="new-entry-key" maxLength={1024} onChange={(event) => setNewKey(event.target.value)} required value={newKey} /></label><label className="field" htmlFor="new-entry-type">Value type<select id="new-entry-type" onChange={(event) => setNewType(event.target.value as EntrySetBody['value']['type'])} value={newType}><option value="STRING">String</option><option value="JSON">JSON</option><option value="LONG">Long</option><option value="BYTES">Bytes (Base64)</option></select></label><label className="field" htmlFor="new-entry-ttl">TTL milliseconds (blank for persistent)<input id="new-entry-ttl" min="1" onChange={(event) => setNewTtl(event.target.value)} type="number" value={newTtl} /></label><label className="field field--wide" htmlFor="new-entry-value">Value<textarea id="new-entry-value" onChange={(event) => setNewValue(event.target.value)} required value={newValue} /></label><div className="modal__actions field--wide"><button className="button button--secondary" onClick={close} type="button">Cancel</button><button className="button" disabled={mutating} type="submit">Create entry</button></div></form></Modal>; }

function EntriesWorkspace({ namespace, actions, children }: { readonly namespace?: string; readonly actions?: ReactNode; readonly children: ReactNode }) { return <section className="workspace" aria-labelledby="entries-title"><div className="workspace__heading"><div><p className="workspace__context">Metadata-only entry inspection</p><h1 id="entries-title">Key Browser</h1>{namespace !== undefined && <p>Namespace: <strong>{namespace}</strong></p>}</div>{actions !== undefined && <div className="workspace__actions">{actions}</div>}</div>{children}</section>; }
function Empty({ title, children }: { readonly title: string; readonly children: ReactNode }) { return <div className="empty-state"><h2>{title}</h2><p>{children}</p></div>; }
function Problem({ problem, stale }: { readonly problem: ManagementClientError; readonly stale: boolean }) { return <div className="diagnostics" role="alert"><strong>{stale ? `Stale data · ${problem.code}` : problem.code}</strong><p>{problem.message}</p>{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</div>; }
function asClientError(failure: unknown): ManagementClientError { return failure instanceof ManagementClientError ? failure : new ManagementClientError(0, 'CONNECTION_FAILED', 'Entry administration could not be completed'); }
function previewExpired(preview: BulkDeletePreview, now: number): boolean { return Date.parse(preview.expiresAt) <= now; }
function positiveInteger(value: string): number { const parsed = Number(value); if (!Number.isSafeInteger(parsed) || parsed < 1) throw new ManagementClientError(400, 'VALIDATION_FAILED', 'TTL must be a positive integer'); return parsed; }
function valueFor(type: EntrySetBody['value']['type'], value: string): CacheValue { switch (type) { case 'STRING': return { type, text: value }; case 'JSON': try { JSON.parse(value); } catch { throw new ManagementClientError(400, 'JSON_VALUE_INVALID', 'Value must be valid JSON'); } return { type, text: value }; case 'LONG': if (!/^-?(?:0|[1-9][0-9]*)$/u.test(value)) throw new ManagementClientError(400, 'VALUE_TYPE_MISMATCH', 'Value must be a signed decimal integer'); return { type, decimal: value }; case 'BYTES': return { type, base64: value }; } }
function formatDecimal(value: string): string { return BigInt(value).toLocaleString('en-US'); }
function formatBytes(value: string): string { return `${formatDecimal(value)} B`; }
function formatTtl(value: number | null): string { return value === null ? 'Persistent' : value < 1_000 ? `${value} ms` : `${(value / 1_000).toFixed(value % 1_000 === 0 ? 0 : 1)} s`; }
function humanize(value: string): string { return value.toLowerCase().replace(/^./u, (letter) => letter.toUpperCase()); }
