import { useEffect, useState, type ReactNode } from 'react';

import type { LockClientPort } from '../../api/resource-client';
import type { LockPage, LockState, RevealedLockOwner } from '../../api/resource-schemas';
import { ManagementClientError } from '../../api/session-client';
import { Modal } from '../../components/Modal';
import { formatDisplayInstant } from '../../presentation/display-time';
import { effectiveAutoHideMillis } from '../../state/preferences';

export function LocksPage({ canOperate, canReveal, client, selectedSetupId }: { readonly canOperate: boolean; readonly canReveal: boolean; readonly client: LockClientPort; readonly selectedSetupId?: string }) {
  const [page, setPage] = useState<LockPage>();
  const [problem, setProblem] = useState<ManagementClientError>();
  const [active, setActive] = useState<LockState>();
  const [revealed, setRevealed] = useState<RevealedLockOwner>();
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (selectedSetupId === undefined) return;
    let mounted = true;
    void client.locks(selectedSetupId, { leaseState: 'ACTIVE', limit: 50 }).then((loaded) => { if (mounted) { setPage(loaded); setProblem(undefined); } }).catch((failure: unknown) => { if (mounted) setProblem(asError(failure)); });
    return () => { mounted = false; };
  }, [client, selectedSetupId]);

  useEffect(() => { if (revealed === undefined) return; const timer = window.setTimeout(() => setRevealed(undefined), effectiveAutoHideMillis(revealed.autoHideAfterMillis)); return () => window.clearTimeout(timer); }, [revealed]);
  useEffect(() => { const hide = () => { if (document.hidden) setRevealed(undefined); }; document.addEventListener('visibilitychange', hide); return () => document.removeEventListener('visibilitychange', hide); }, []);

  const manage = async (item: LockState) => {
    if (selectedSetupId === undefined) return;
    setBusy(true); setRevealed(undefined); setProblem(undefined);
    try { setActive(await client.lock(selectedSetupId, item.encodedNamespace, item.encodedKey)); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };
  const reveal = async () => {
    if (selectedSetupId === undefined || active === undefined) return;
    setBusy(true);
    try { setRevealed(await client.revealLockOwner(selectedSetupId, active.encodedNamespace, active.encodedKey, reason || undefined)); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };
  const prepareRelease = async () => {
    if (selectedSetupId === undefined || active === undefined) return;
    setBusy(true);
    try { setActive(await client.lock(selectedSetupId, active.encodedNamespace, active.encodedKey)); setConfirming(true); setConfirmation(''); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };
  const release = async () => {
    if (selectedSetupId === undefined || active === undefined || confirmation !== active.key) return;
    setBusy(true);
    try { await client.forceReleaseLock(selectedSetupId, active.encodedNamespace, active.encodedKey, active.version, confirmation, reason || undefined); setStatus('Lock released after exact-version confirmation.'); setConfirming(false); setActive(undefined); setRevealed(undefined); setPage((current) => current === undefined ? current : { ...current, items: current.items.filter((item) => item.encodedNamespace !== active.encodedNamespace || item.encodedKey !== active.encodedKey) }); }
    catch (failure: unknown) { setProblem(asError(failure)); }
    finally { setBusy(false); }
  };

  if (selectedSetupId === undefined) return <Workspace><p>Select a connected setup before inspecting locks.</p></Workspace>;
  return <Workspace>{problem !== undefined && <div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p></div>}{status !== '' && <p role="status">{status}</p>}{page === undefined ? <p aria-busy="true">Loading locks…</p> : page.items.length === 0 ? <p>No active locks.</p> : <div className="table-scroll"><table className="data-table"><thead><tr><th>Namespace</th><th>Key</th><th>Fencing token</th><th>Version</th><th>Lease expires</th><th>Remaining</th><th>Owner</th>{canOperate && <th>Actions</th>}</tr></thead><tbody>{page.items.map((item) => <tr key={`${item.encodedNamespace}:${item.encodedKey}`}><td>{item.namespace}</td><td>{item.key}</td><td>{item.fencingToken}</td><td>{item.version}</td><td>{formatDisplayInstant(item.leaseExpiresAt)}</td><td>{item.leaseRemainingMillis} ms</td><td>Masked</td>{canOperate && <td><button className="button button--quiet" disabled={busy} onClick={() => void manage(item)} type="button">Manage {item.key}</button></td>}</tr>)}</tbody></table></div>}
    {active !== undefined && !confirming && <Modal labelId="lock-title" onDismiss={() => { setActive(undefined); setRevealed(undefined); }}><h2 id="lock-title">Manage {active.key}</h2><p>Version {active.version} · fencing token {active.fencingToken}</p><label className="field" htmlFor="lock-reason">Reason (optional)<input id="lock-reason" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} value={reason} /></label><section className="details-section" aria-labelledby="lock-owner-title"><h3 id="lock-owner-title">Owner</h3>{revealed === undefined ? <p>Masked</p> : <p className="value-content">{revealed.ownerToken}</p>}{canReveal && (revealed === undefined ? <button className="button" disabled={busy} onClick={() => void reveal()} type="button">Reveal owner</button> : <button className="button" onClick={() => setRevealed(undefined)} type="button">Hide owner</button>)}</section>{canOperate && <button className="button button--danger" disabled={busy} onClick={() => void prepareRelease()} type="button">Force release</button>}<div className="modal__actions"><button className="button button--secondary" onClick={() => { setActive(undefined); setRevealed(undefined); }} type="button">Close</button></div></Modal>}
    {confirming && active !== undefined && <Modal labelId="release-title" onDismiss={() => setConfirming(false)}><h2 id="release-title">Release current lock version?</h2><p>The lock was reloaded immediately before this confirmation. A renewal or reacquisition will cause a conflict.</p><label className="field" htmlFor="lock-confirmation">Confirm lock key<input id="lock-confirmation" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} /></label><div className="modal__actions"><button className="button button--secondary" onClick={() => setConfirming(false)} type="button">Cancel</button><button className="button button--danger" disabled={busy || confirmation !== active.key} onClick={() => void release()} type="button">Release current version</button></div></Modal>}
  </Workspace>;
}

function Workspace({ children }: { children: ReactNode }) { return <section className="workspace" aria-labelledby="locks-title"><p className="workspace__context">Active database leases</p><h1 id="locks-title">Locks</h1>{children}</section>; }
function asError(value: unknown) { return value instanceof ManagementClientError ? value : new ManagementClientError(0, 'CONNECTION_FAILED', 'Lock operation failed'); }
