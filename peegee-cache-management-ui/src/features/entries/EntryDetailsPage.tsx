import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { EntryAdministrationClientPort } from '../../api/entry-administration-client';
import type { EntrySetBody } from '../../api/entry-administration-schemas';
import type { EntryDetailsClientPort } from '../../api/inspection-client';
import type { CacheValue, EntryMetadata, RevealedEntryValue } from '../../api/inspection-schemas';
import { ManagementClientError } from '../../api/session-client';
import { Modal } from '../../components/Modal';
import { formatDisplayInstant } from '../../presentation/display-time';
import { effectiveAutoHideMillis } from '../../state/preferences';
import { EntryValueFormatter } from './EntryValueFormatter';
import { copyableValue } from './value-formatting';

interface EntryDetailsPageProps {
  readonly administrationClient?: EntryAdministrationClientPort;
  readonly canOperate?: boolean;
  readonly canReveal: boolean;
  readonly client: EntryDetailsClientPort;
  readonly encodedKey: string;
  readonly encodedNamespace: string;
  readonly selectedSetupId?: string;
}

export function EntryDetailsPage({ administrationClient, canOperate = false, canReveal, client, encodedKey, encodedNamespace, selectedSetupId }: EntryDetailsPageProps) {
  const [metadataState, setMetadataState] = useState<{ scope: string; value: EntryMetadata }>();
  const [problem, setProblem] = useState<ManagementClientError>();
  const [revealed, setRevealed] = useState<RevealedEntryValue>();
  const [reason, setReason] = useState('');
  const [revealing, setRevealing] = useState(false);
  const [copyStatus, setCopyStatus] = useState('');
  const [operationStatus, setOperationStatus] = useState('');
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState('');
  const [setMode, setSetMode] = useState<EntrySetBody['setMode']>('ONLY_IF_VERSION_MATCHES');
  const [ttlMode, setTtlMode] = useState<EntrySetBody['ttlMode']>('PRESERVE_EXISTING');
  const [editTtlMillis, setEditTtlMillis] = useState('');
  const [ttlMillis, setTtlMillis] = useState('');
  const [refreshTtlMillis, setRefreshTtlMillis] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [deleteConfirmation, setDeleteConfirmation] = useState('');
  const [mutating, setMutating] = useState(false);
  const [activeScope, setActiveScope] = useState(sensitiveScopeKey(selectedSetupId, encodedNamespace, encodedKey, canReveal));
  const generation = useRef(0);
  const scope = `${selectedSetupId ?? ''}:${encodedNamespace}:${encodedKey}`;
  const sensitiveScope = sensitiveScopeKey(selectedSetupId, encodedNamespace, encodedKey, canReveal);
  const metadata = metadataState?.scope === scope ? metadataState.value : undefined;

  if (activeScope !== sensitiveScope) {
    setActiveScope(sensitiveScope);
    setRevealed(undefined);
    setReason('');
    setCopyStatus('');
    setOperationStatus('');
    setEditing(false);
    setDeleting(false);
  }

  useLayoutEffect(() => { generation.current++; }, [sensitiveScope]);

  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    let active = true;
    void client.entry(selectedSetupId, encodedNamespace, encodedKey, true)
      .then((loaded) => {
        if (!active) return;
        requireMatchingRoute(loaded, encodedNamespace, encodedKey);
        setMetadataState({ scope, value: loaded });
        setProblem(undefined);
      })
      .catch((failure: unknown) => { if (active) setProblem(asClientError(failure)); });
    return () => { active = false; };
  }, [client, encodedKey, encodedNamespace, scope, selectedSetupId]);

  useEffect(() => {
    if (revealed === undefined) return undefined;
    const timer = window.setTimeout(() => setRevealed(undefined), effectiveAutoHideMillis(revealed.autoHideAfterMillis));
    return () => window.clearTimeout(timer);
  }, [revealed]);

  useEffect(() => {
    const hideWhenBackgrounded = () => { if (document.hidden) setRevealed(undefined); };
    document.addEventListener('visibilitychange', hideWhenBackgrounded);
    return () => document.removeEventListener('visibilitychange', hideWhenBackgrounded);
  }, []);

  const reloadMetadata = async (): Promise<EntryMetadata | undefined> => {
    if (selectedSetupId === undefined) return undefined;
    const loaded = await client.entry(selectedSetupId, encodedNamespace, encodedKey, true);
    requireMatchingRoute(loaded, encodedNamespace, encodedKey);
    setMetadataState({ scope, value: loaded });
    return loaded;
  };

  const reveal = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (selectedSetupId === undefined || metadata === undefined || !canReveal) return;
    const acceptedGeneration = generation.current;
    setRevealing(true);
    setProblem(undefined);
    try {
      const loaded = await client.revealEntryValue(selectedSetupId, encodedNamespace, encodedKey, reason || undefined);
      if (acceptedGeneration !== generation.current) return;
      if (loaded.key !== metadata.key) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned a revealed value for a different entry');
      setRevealed(loaded);
      setCopyStatus('');
    } catch (failure: unknown) {
      if (acceptedGeneration === generation.current) setProblem(asClientError(failure));
    } finally {
      if (acceptedGeneration === generation.current) setRevealing(false);
    }
  };

  const copy = async () => {
    if (revealed === undefined) return;
    try {
      await globalThis.navigator.clipboard.writeText(copyableValue(revealed.value));
      setCopyStatus('Copied');
    } catch {
      setCopyStatus('Copy failed');
    }
  };

  const saveEntry = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (administrationClient === undefined || selectedSetupId === undefined || metadata === undefined) return;
    setMutating(true);
    setProblem(undefined);
    setOperationStatus('');
    try {
      const body: EntrySetBody = {
        value: editedValue(metadata.valueType, editValue),
        ttlMode,
        ttlMillis: ttlMode === 'REPLACE' ? positiveInteger(editTtlMillis, 'Replacement TTL') : null,
        setMode,
      };
      const result = await administrationClient.setEntry(selectedSetupId, encodedNamespace, encodedKey, body, setMode === 'ONLY_IF_VERSION_MATCHES' ? metadata.version : undefined);
      setOperationStatus(`Entry ${result.created ? 'created' : 'updated'} at ${formatDisplayInstant(result.updatedAt)} · version ${result.version}`);
      setEditing(false);
      setEditValue('');
      await reloadMetadata();
    } catch (failure: unknown) {
      const clientFailure = asClientError(failure);
      setProblem(clientFailure);
      if (clientFailure.status === 409 || clientFailure.status === 412) {
        try { await reloadMetadata(); } catch { /* Preserve conflict and form input. */ }
      }
    } finally {
      setMutating(false);
    }
  };

  const updateMetadata = async (operation: () => Promise<EntryMetadata>, message: string) => {
    setMutating(true);
    setProblem(undefined);
    setOperationStatus('');
    try {
      const updated = await operation();
      setMetadataState({ scope, value: updated });
      setOperationStatus(message);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setMutating(false);
    }
  };

  const confirmDelete = async () => {
    if (administrationClient === undefined || selectedSetupId === undefined || metadata === undefined) return;
    setMutating(true);
    setProblem(undefined);
    try {
      await administrationClient.deleteEntry(selectedSetupId, encodedNamespace, encodedKey, metadata.version);
      setDeleting(false);
      setDeleteConfirmation('');
      setOperationStatus('Entry deleted. Return to the Key Browser to continue.');
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setMutating(false);
    }
  };

  if (selectedSetupId === undefined) return <Workspace title="Entry details"><Empty>Select a connected setup before inspecting an entry.</Empty></Workspace>;
  if (metadata === undefined && problem !== undefined) return <Workspace title="Entry details"><Problem problem={problem} /></Workspace>;
  if (metadata === undefined) return <Workspace title="Entry details"><p aria-busy="true">Loading entry metadata…</p></Workspace>;

  return <Workspace title={metadata.key}>
    {problem !== undefined && <Problem problem={problem} />}
    <p className="scope-label">Namespace: <strong>{metadata.namespace}</strong></p>
    <dl className="details-list" aria-label="Entry metadata">
      <Detail label="Type" value={metadata.valueType} /><Detail label="Size" value={`${BigInt(metadata.sizeBytes).toLocaleString('en-US')} B`} /><Detail label="Version" value={metadata.version} /><Detail label="Created" value={formatDisplayInstant(metadata.createdAt)} /><Detail label="Updated" value={formatDisplayInstant(metadata.updatedAt)} /><Detail label="Last accessed" value={formatOptionalInstant(metadata.lastAccessedAt)} /><Detail label="Expires" value={formatOptionalInstant(metadata.ttl.expiresAt)} /><Detail label="Remaining TTL" value={formatNullableDuration(metadata.ttl.ttlMillis)} /><Detail label="Status" value={metadata.ttl.state} />
    </dl>
    {operationStatus !== '' && <p role="status">{operationStatus}</p>}
    {canOperate && administrationClient !== undefined && <AdministrationSection administrationClient={administrationClient} deleting={deleting} editTtlMillis={editTtlMillis} editValue={editValue} editing={editing} encodedKey={encodedKey} encodedNamespace={encodedNamespace} metadata={metadata} mutating={mutating} refreshTtlMillis={refreshTtlMillis} saveEntry={saveEntry} selectedSetupId={selectedSetupId} setDeleting={setDeleting} setEditing={setEditing} setEditTtlMillis={setEditTtlMillis} setEditValue={setEditValue} setMode={setSetMode} setRefreshTtlMillis={setRefreshTtlMillis} setTtlMillis={setTtlMillis} setTtlMode={setTtlMode} ttlMillis={ttlMillis} ttlMode={ttlMode} updateMetadata={updateMetadata} setModeValue={setMode} />}
    <ValuePanel canReveal={canReveal} copy={copy} copyStatus={copyStatus} reason={reason} reveal={reveal} revealed={revealed} revealing={revealing} setReason={setReason} setRevealed={setRevealed} />
    {deleting && <DeleteDialog confirmation={deleteConfirmation} confirmDelete={confirmDelete} keyName={metadata.key} mutating={mutating} setConfirmation={setDeleteConfirmation} setDeleting={setDeleting} />}
  </Workspace>;
}

interface AdministrationSectionProps {
  administrationClient: EntryAdministrationClientPort; deleting: boolean; editTtlMillis: string; editValue: string; editing: boolean; encodedKey: string; encodedNamespace: string; metadata: EntryMetadata; mutating: boolean; refreshTtlMillis: string; saveEntry: (event: FormEvent<HTMLFormElement>) => Promise<void>; selectedSetupId: string; setDeleting: (value: boolean) => void; setEditing: (value: boolean) => void; setEditTtlMillis: (value: string) => void; setEditValue: (value: string) => void; setMode: (value: EntrySetBody['setMode']) => void; setRefreshTtlMillis: (value: string) => void; setTtlMillis: (value: string) => void; setTtlMode: (value: EntrySetBody['ttlMode']) => void; ttlMillis: string; ttlMode: EntrySetBody['ttlMode']; updateMetadata: (operation: () => Promise<EntryMetadata>, message: string) => Promise<void>; setModeValue: EntrySetBody['setMode'];
}

function AdministrationSection(props: AdministrationSectionProps) {
  const { administrationClient, editTtlMillis, editValue, editing, encodedKey, encodedNamespace, metadata, mutating, refreshTtlMillis, saveEntry, selectedSetupId, setDeleting, setEditing, setEditTtlMillis, setEditValue, setMode, setRefreshTtlMillis, setTtlMillis, setTtlMode, ttlMillis, ttlMode, updateMetadata, setModeValue } = props;
  return <section className="details-section" aria-labelledby="entry-administration-title">
    <div className="section-heading"><div><h2 id="entry-administration-title">Entry administration</h2><p>Success is shown only after a validated server response.</p></div><button className="button" onClick={() => setEditing(!editing)} type="button">{editing ? 'Cancel edit' : 'Edit entry'}</button></div>
    {editing && <form className="form-grid" onSubmit={(event) => void saveEntry(event)}>
      <label className="field" htmlFor="entry-edit-type">Value type<select disabled id="entry-edit-type" value={metadata.valueType}><option>{metadata.valueType}</option></select></label>
      <label className="field" htmlFor="entry-set-mode">Set mode<select id="entry-set-mode" onChange={(event) => setMode(event.target.value as EntrySetBody['setMode'])} value={setModeValue}><option value="ONLY_IF_VERSION_MATCHES">Only if observed version matches</option><option value="UPSERT">Always / upsert</option><option value="ONLY_IF_PRESENT">Only if present</option><option value="ONLY_IF_ABSENT">Only if absent</option></select></label>
      <label className="field field--wide" htmlFor="entry-edit-value">Entry value<textarea id="entry-edit-value" onChange={(event) => setEditValue(event.target.value)} required value={editValue} /></label>
      <label className="field" htmlFor="entry-ttl-mode">TTL behavior<select id="entry-ttl-mode" onChange={(event) => setTtlMode(event.target.value as EntrySetBody['ttlMode'])} value={ttlMode}><option value="PRESERVE_EXISTING">Preserve existing</option><option value="USE_DEFAULT">Use cache default</option><option value="REPLACE">Replace TTL</option><option value="REMOVE">Make persistent</option></select></label>
      <label className="field" htmlFor="entry-edit-ttl">Replacement TTL milliseconds<input disabled={ttlMode !== 'REPLACE'} id="entry-edit-ttl" min="1" onChange={(event) => setEditTtlMillis(event.target.value)} required={ttlMode === 'REPLACE'} type="number" value={editTtlMillis} /></label>
      <div className="modal__actions field--wide"><button className="button" disabled={mutating} type="submit">Save entry</button></div>
    </form>}
    <div className="form-grid">
      <label className="field" htmlFor="entry-ttl-millis">TTL milliseconds<input id="entry-ttl-millis" min="1" onChange={(event) => setTtlMillis(event.target.value)} type="number" value={ttlMillis} /></label>
      <div className="workspace__actions"><button className="button" disabled={mutating || ttlMillis === ''} onClick={() => void updateMetadata(() => administrationClient.expireEntry(selectedSetupId, encodedNamespace, encodedKey, metadata.version, positiveInteger(ttlMillis, 'TTL')), 'Entry TTL updated from the committed server result.')} type="button">Set TTL</button><button className="button button--secondary" disabled={mutating} onClick={() => void updateMetadata(() => administrationClient.persistEntry(selectedSetupId, encodedNamespace, encodedKey, metadata.version), 'Entry is persistent.')} type="button">Make persistent</button></div>
      <label className="field" htmlFor="entry-refresh-ttl">Refresh TTL milliseconds (optional)<input id="entry-refresh-ttl" min="1" onChange={(event) => setRefreshTtlMillis(event.target.value)} type="number" value={refreshTtlMillis} /></label>
      <div className="workspace__actions"><button className="button button--secondary" disabled={mutating} onClick={() => void updateMetadata(() => administrationClient.touchEntry(selectedSetupId, encodedNamespace, encodedKey, metadata.version, refreshTtlMillis === '' ? null : positiveInteger(refreshTtlMillis, 'Refresh TTL')), 'Entry touched using authoritative metadata.')} type="button">Touch entry</button><button className="button button--danger" disabled={mutating} onClick={() => setDeleting(true)} type="button">Delete entry</button></div>
    </div>
  </section>;
}

function ValuePanel({ canReveal, copy, copyStatus, reason, reveal, revealed, revealing, setReason, setRevealed }: { canReveal: boolean; copy: () => Promise<void>; copyStatus: string; reason: string; reveal: (event: FormEvent<HTMLFormElement>) => Promise<void>; revealed?: RevealedEntryValue; revealing: boolean; setReason: (value: string) => void; setRevealed: (value?: RevealedEntryValue) => void }) {
  return <section className="value-panel" aria-labelledby="value-title"><div className="workspace__heading"><div><p className="workspace__context">Sensitive data</p><h2 id="value-title">Value</h2></div>{revealed !== undefined && <div className="workspace__actions"><button className="button button--secondary" onClick={() => void copy()} type="button">Copy revealed value</button><button className="button" onClick={() => setRevealed(undefined)} type="button">Hide value</button></div>}</div>{revealed === undefined ? <div className="masked-value"><strong>Value hidden</strong><p>Values are excluded from ordinary metadata requests and remain hidden until explicitly revealed.</p>{canReveal ? <form className="reveal-form" onSubmit={(event) => void reveal(event)}><label className="field" htmlFor="reveal-reason">Reveal reason (optional)<input id="reveal-reason" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} value={reason} /></label><button className="button" disabled={revealing} type="submit">{revealing ? 'Revealing…' : 'Reveal value'}</button></form> : <p>Operator permission and sensitive-reveal capability are required.</p>}</div> : <><p className="scope-label">Revealed at {formatDisplayInstant(revealed.revealedAt)} · Automatically hidden after {formatDuration(effectiveAutoHideMillis(revealed.autoHideAfterMillis))}</p><EntryValueFormatter value={revealed.value} />{copyStatus !== '' && <p aria-live="polite">{copyStatus}</p>}</>}</section>;
}

function DeleteDialog({ confirmation, confirmDelete, keyName, mutating, setConfirmation, setDeleting }: { confirmation: string; confirmDelete: () => Promise<void>; keyName: string; mutating: boolean; setConfirmation: (value: string) => void; setDeleting: (value: boolean) => void }) { const close = () => setDeleting(false); return <Modal labelId="delete-entry-title" onDismiss={close}><h2 id="delete-entry-title">Delete {keyName}?</h2><p>This exact version is deleted only if it has not changed.</p><label className="field" htmlFor="delete-entry-confirmation">Confirm entry key<input id="delete-entry-confirmation" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} /></label><div className="modal__actions"><button className="button button--secondary" onClick={close} type="button">Cancel</button><button className="button button--danger" disabled={confirmation !== keyName || mutating} onClick={() => void confirmDelete()} type="button">Confirm delete</button></div></Modal>; }
function Workspace({ title, children }: { readonly title: string; readonly children: ReactNode }) { return <section className="workspace" aria-labelledby="entry-title"><div className="workspace__heading"><div><p className="workspace__context"><Link to="/keys">Key Browser</Link></p><h1 id="entry-title">{title}</h1></div></div>{children}</section>; }
function Detail({ label, value }: { readonly label: string; readonly value: string }) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function Empty({ children }: { readonly children: ReactNode }) { return <div className="empty-state"><h2>Entry unavailable</h2><p>{children}</p></div>; }
function Problem({ problem }: { readonly problem: ManagementClientError }) { return <div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p>{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</div>; }
function asClientError(failure: unknown): ManagementClientError { return failure instanceof ManagementClientError ? failure : new ManagementClientError(0, 'CONNECTION_FAILED', 'Entry administration could not be completed'); }
function formatDuration(milliseconds: number): string { return milliseconds < 1_000 ? `${milliseconds} ms` : `${milliseconds / 1_000} s`; }
function formatNullableDuration(milliseconds: number | null): string { return milliseconds === null ? 'Persistent' : formatDuration(milliseconds); }
function formatOptionalInstant(instant: string | null | undefined): string { return instant === null || instant === undefined ? 'Never' : formatDisplayInstant(instant); }
function sensitiveScopeKey(setupId: string | undefined, encodedNamespace: string, encodedKey: string, canReveal: boolean): string { return `${setupId ?? ''}:${encodedNamespace}:${encodedKey}:${canReveal}`; }
function requireMatchingRoute(loaded: EntryMetadata, encodedNamespace: string, encodedKey: string): void { if (loaded.encodedNamespace !== encodedNamespace || loaded.encodedKey !== encodedKey) throw new ManagementClientError(502, 'RESPONSE_CONTRACT_INVALID', 'The server returned entry metadata for a different route'); }
function positiveInteger(value: string, label: string): number { const parsed = Number(value); if (!Number.isSafeInteger(parsed) || parsed < 1) throw new ManagementClientError(400, 'VALIDATION_FAILED', `${label} must be a positive integer`); return parsed; }
function editedValue(type: EntryMetadata['valueType'], value: string): CacheValue { switch (type) { case 'STRING': return { type, text: value }; case 'JSON': try { JSON.parse(value); } catch { throw new ManagementClientError(400, 'JSON_VALUE_INVALID', 'Value must be valid JSON'); } return { type, text: value }; case 'LONG': if (!/^-?(?:0|[1-9][0-9]*)$/u.test(value)) throw new ManagementClientError(400, 'VALUE_TYPE_MISMATCH', 'Value must be a signed decimal integer'); return { type, decimal: value }; case 'BYTES': return { type, base64: value }; } }
