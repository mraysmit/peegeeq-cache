import { useEffect, useState, type ReactNode } from 'react';

import type { BackendCapabilityClientPort } from '../../api/backend-capability-client';
import type {
  BatchDeleteResult,
  BatchGetResult,
  BatchSetResult,
  CacheMetricsSnapshot,
  CoreCacheSetRequest,
  ScanEntriesResult,
} from '../../api/backend-capability-schemas';
import { batchSetRequestSchema } from '../../api/backend-capability-schemas';
import { encodeKey, encodeNamespace } from '../../api/identifier-codec';
import { ManagementClientError } from '../../api/session-client';

type Props = {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly canBatch: boolean;
  readonly canScan: boolean;
  readonly canMetrics: boolean;
  readonly canOwnLocks: boolean;
  readonly client: BackendCapabilityClientPort;
  readonly selectedSetupId?: string;
};

export function AdvancedOperationsPage({ canOperate, canReveal, canBatch, canScan, canMetrics, canOwnLocks, client, selectedSetupId }: Props) {
  const [namespace, setNamespace] = useState('');
  const [key, setKey] = useState('');
  const [exists, setExists] = useState<boolean>();
  const [batchKeys, setBatchKeys] = useState('');
  const [batchDeleteKeys, setBatchDeleteKeys] = useState('');
  const [batchValues, setBatchValues] = useState('');
  const [reason, setReason] = useState('Interactive console operation');
  const [batchGet, setBatchGet] = useState<BatchGetResult>();
  const [batchSet, setBatchSet] = useState<BatchSetResult>();
  const [batchDelete, setBatchDelete] = useState<BatchDeleteResult>();
  const [scanPrefix, setScanPrefix] = useState('');
  const [scanLimit, setScanLimit] = useState('50');
  const [scanValues, setScanValues] = useState(true);
  const [scanExpired, setScanExpired] = useState(false);
  const [scan, setScan] = useState<ScanEntriesResult>();
  const [metrics, setMetrics] = useState<CacheMetricsSnapshot>();
  const [lockNamespace, setLockNamespace] = useState('');
  const [lockKey, setLockKey] = useState('');
  const [ownerToken, setOwnerToken] = useState('');
  const [leaseTtl, setLeaseTtl] = useState('30000');
  const [reentrant, setReentrant] = useState(false);
  const [fencing, setFencing] = useState(true);
  const [lockResult, setLockResult] = useState('');
  const [problem, setProblem] = useState<ManagementClientError>();
  const [status, setStatus] = useState('');
  const [busy, setBusy] = useState(false);

  const clearSensitive = () => {
    setBatchGet(undefined);
    setBatchSet(undefined);
    setBatchDelete(undefined);
    setScan(undefined);
    setOwnerToken('');
    setLockResult('');
  };

  useEffect(() => {
    const clear = () => {
      setBatchGet(undefined);
      setBatchSet(undefined);
      setBatchDelete(undefined);
      setScan(undefined);
      setOwnerToken('');
      setLockResult('');
    };
    const visibility = () => { if (document.hidden) clear(); };
    document.addEventListener('visibilitychange', visibility);
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      clear();
    };
  }, []);

  const run = async (operation: () => Promise<void>, message: string) => {
    setBusy(true);
    setProblem(undefined);
    setStatus('');
    try {
      await operation();
      setStatus(message);
    } catch (failure: unknown) {
      setProblem(asError(failure));
    } finally {
      setBusy(false);
    }
  };

  if (selectedSetupId === undefined) {
    return <Workspace><p>Select a connected setup before using advanced operations.</p></Workspace>;
  }

  const checkExists = () => void run(async () => {
    setExists(await client.entryExists(
      selectedSetupId, encodeNamespace(required(namespace, 'Namespace')), encodeKey(required(key, 'Key')),
    ));
  }, 'Existence check completed');

  const getMany = () => void run(async () => {
    setBatchGet(await client.batchGetEntries(selectedSetupId, {
      keys: parseKeys(batchKeys),
      reason: required(reason, 'Reason'),
    }));
  }, 'Batch get completed');

  const setMany = () => void run(async () => {
    setBatchSet(await client.batchSetEntries(selectedSetupId, {
      entries: parseSetEntries(batchValues),
    }));
  }, 'Batch set completed');

  const deleteMany = () => void run(async () => {
    setBatchDelete(await client.batchDeleteEntries(selectedSetupId, {
      keys: parseKeys(batchDeleteKeys),
    }));
  }, 'Batch delete completed');

  const scanEntries = (cursor: string | null = null) => void run(async () => {
    setScan(await client.scanEntries(selectedSetupId, {
      namespace: required(namespace, 'Namespace'),
      prefix: scanPrefix.trim() === '' ? null : scanPrefix,
      cursor,
      limit: positiveInt(scanLimit, 200, 'Scan limit'),
      includeValues: scanValues,
      includeExpired: scanExpired,
      reason: required(reason, 'Reason'),
    }));
  }, 'Backend scan completed');

  const loadMetrics = () => void run(async () => {
    setMetrics(await client.cacheMetrics(selectedSetupId));
  }, 'Core metrics refreshed');

  const acquire = () => void run(async () => {
    const result = await client.acquireLock(
      selectedSetupId,
      encodeNamespace(required(lockNamespace, 'Lock namespace')),
      encodeKey(required(lockKey, 'Lock key')),
      {
        ownerToken: required(ownerToken, 'Owner token'),
        leaseTtlMillis: positiveInt(leaseTtl, Number.MAX_SAFE_INTEGER, 'Lease TTL'),
        reentrantForSameOwner: reentrant,
        issueFencingToken: fencing,
      },
    );
    setLockResult(result.acquired
      ? `Acquired · fencing ${result.fencingToken ?? 'not requested'} · expires ${result.leaseExpiresAt ?? 'unknown'}`
      : 'Not acquired');
  }, 'Lock acquisition completed');

  const renew = () => lockBoolean(
    () => client.renewLock(selectedSetupId, encodedLockNamespace(), encodedLockKey(), {
      ownerToken: required(ownerToken, 'Owner token'),
      leaseTtlMillis: positiveInt(leaseTtl, Number.MAX_SAFE_INTEGER, 'Lease TTL'),
    }),
    'Renewed',
    'Not renewed',
  );
  const release = () => lockBoolean(
    () => client.releaseLock(
      selectedSetupId, encodedLockNamespace(), encodedLockKey(), required(ownerToken, 'Owner token'),
    ),
    'Released by owner',
    'Not released',
  );
  const ownership = () => lockBoolean(
    () => client.isLockHeldBy(
      selectedSetupId, encodedLockNamespace(), encodedLockKey(), required(ownerToken, 'Owner token'),
    ),
    'Owner token holds this lock',
    'Owner token does not hold this lock',
  );

  function encodedLockNamespace() { return encodeNamespace(required(lockNamespace, 'Lock namespace')); }
  function encodedLockKey() { return encodeKey(required(lockKey, 'Lock key')); }
  function lockBoolean(operation: () => Promise<boolean>, yes: string, no: string) {
    void run(async () => setLockResult(await operation() ? yes : no), 'Lock operation completed');
  }

  return <Workspace>
    <p>Direct workflows for the complete public cache, scan, lock, and metrics service surface.</p>
    {problem !== undefined && <div className="diagnostics" role="alert"><strong>{problem.code}</strong><p>{problem.message}</p></div>}
    {status !== '' && <p role="status">{status}</p>}

    <Panel title="Existence and scan">
      <TwoColumns>
        <Field id="advanced-namespace" label="Namespace" onChange={setNamespace} value={namespace} />
        <Field id="advanced-key" label="Key" onChange={setKey} value={key} />
      </TwoColumns>
      <button className="button" disabled={busy || namespace.trim() === '' || key.trim() === ''} onClick={checkExists} type="button">Check entry existence</button>
      {exists !== undefined && <p><strong>Exists:</strong> {exists ? 'Yes' : 'No'}</p>}
      {canReveal && canScan && <>
        <TwoColumns>
          <Field id="scan-prefix" label="Scan key prefix (optional)" onChange={setScanPrefix} value={scanPrefix} />
          <Field id="scan-limit" label="Scan page size" onChange={setScanLimit} type="number" value={scanLimit} />
        </TwoColumns>
        <label><input checked={scanValues} onChange={(event) => setScanValues(event.target.checked)} type="checkbox" /> Include values</label>{' '}
        <label><input checked={scanExpired} onChange={(event) => setScanExpired(event.target.checked)} type="checkbox" /> Include expired entries</label>
        <div><button className="button" disabled={busy || namespace.trim() === ''} onClick={() => scanEntries()} type="button">Run backend scan</button></div>
        {scan !== undefined && <Result title="Scan result" value={scan} />}
        {scan?.hasMore === true && <button className="button button--secondary" disabled={busy} onClick={() => scanEntries(scan.nextCursor)} type="button">Load next scan page</button>}
      </>}
    </Panel>

    {canReveal && canBatch && <Panel title="Batch get">
      <p>One entry per line: <code>namespace[TAB]key</code>.</p>
      <TextArea id="batch-keys" label="Batch get keys" onChange={setBatchKeys} value={batchKeys} />
      <Field id="advanced-reason" label="Audit reason" onChange={setReason} value={reason} />
      <button className="button" disabled={busy || batchKeys.trim() === ''} onClick={getMany} type="button">Get entry batch</button>
      {batchGet !== undefined && <Result title="Batch get result" value={batchGet} />}
    </Panel>}

    {canOperate && canBatch && <Panel title="Batch set">
      <p>Provide a JSON array. Every entry independently specifies namespace, key, typed value, TTL, set mode, expected version, and whether to return its previous value.</p>
      <TextArea id="batch-values" label="Batch set entries (JSON)" onChange={setBatchValues} value={batchValues} />
      <div><button className="button" disabled={busy || batchValues.trim() === ''} onClick={setMany} type="button">Set entry batch</button></div>
      {batchSet !== undefined && <Result title="Batch set result" value={batchSet} />}
    </Panel>}

    {canOperate && canBatch && <Panel title="Cross-namespace batch delete">
      <p>One entry per line: <code>namespace[TAB]key</code>. This invokes the core cache service’s exact multi-key delete operation.</p>
      <TextArea id="batch-delete-keys" label="Batch delete keys" onChange={setBatchDeleteKeys} value={batchDeleteKeys} />
      <button className="button button--danger" disabled={busy || batchDeleteKeys.trim() === ''} onClick={deleteMany} type="button">Delete entry batch</button>
      {batchDelete !== undefined && <Result title="Batch delete result" value={batchDelete} />}
    </Panel>}

    {canMetrics && <Panel title="Exact core metrics">
      <button className="button" disabled={busy} onClick={loadMetrics} type="button">Refresh core metrics</button>
      {metrics !== undefined && <dl className="details-list">{Object.entries(metrics).map(([name, value]) => <div key={name}><dt>{words(name)}</dt><dd>{BigInt(value).toLocaleString('en-US')}</dd></div>)}</dl>}
    </Panel>}

    {canOperate && canOwnLocks && <Panel title="Owner lock lifecycle">
      <TwoColumns>
        <Field id="lock-namespace" label="Lock namespace" onChange={setLockNamespace} value={lockNamespace} />
        <Field id="lock-key" label="Lock key" onChange={setLockKey} value={lockKey} />
        <Field id="lock-owner" label="Owner token" onChange={setOwnerToken} type="password" value={ownerToken} />
        <Field id="lock-lease" label="Lease TTL milliseconds" onChange={setLeaseTtl} type="number" value={leaseTtl} />
      </TwoColumns>
      <label><input checked={reentrant} onChange={(event) => setReentrant(event.target.checked)} type="checkbox" /> Reentrant for the same owner</label>{' '}
      <label><input checked={fencing} onChange={(event) => setFencing(event.target.checked)} type="checkbox" /> Issue fencing token</label>
      <div className="workspace__actions"><button className="button" disabled={busy} onClick={acquire} type="button">Acquire lock</button><button className="button" disabled={busy} onClick={renew} type="button">Renew lock</button><button className="button" disabled={busy} onClick={release} type="button">Release by owner</button>{canReveal && <button className="button button--secondary" disabled={busy} onClick={ownership} type="button">Check ownership</button>}<button className="button button--secondary" onClick={clearSensitive} type="button">Clear sensitive state</button></div>
      {lockResult !== '' && <p><strong>Lock result:</strong> {lockResult}</p>}
    </Panel>}
  </Workspace>;
}

function Workspace({ children }: { readonly children: ReactNode }) { return <section aria-labelledby="advanced-title" className="workspace"><div className="workspace__heading"><div><p className="workspace__context">Complete backend service exposure</p><h1 id="advanced-title">Advanced operations</h1></div></div>{children}</section>; }
function Panel({ children, title }: { readonly children: ReactNode; readonly title: string }) { const id = `advanced-${title.toLowerCase().replaceAll(' ', '-')}`; return <section aria-labelledby={id} className="overview-panel"><h2 id={id}>{title}</h2>{children}</section>; }
function TwoColumns({ children }: { readonly children: ReactNode }) { return <div className="form-grid">{children}</div>; }
function Field({ id, label, onChange, type = 'text', value }: { readonly id: string; readonly label: string; readonly onChange: (value: string) => void; readonly type?: string; readonly value: string }) { return <label className="field" htmlFor={id}>{label}<input id={id} onChange={(event) => onChange(event.target.value)} type={type} value={value} /></label>; }
function TextArea({ id, label, onChange, value }: { readonly id: string; readonly label: string; readonly onChange: (value: string) => void; readonly value: string }) { return <label className="field" htmlFor={id}>{label}<textarea id={id} onChange={(event) => onChange(event.target.value)} rows={6} value={value} /></label>; }
function Result({ title, value }: { readonly title: string; readonly value: unknown }) { return <section aria-label={title}><div className="workspace__actions"><h3>{title}</h3></div><pre>{JSON.stringify(value, null, 2)}</pre></section>; }

function parseKeys(value: string) {
  return lines(value).map((line) => {
    const [namespace, key, ...extra] = line.split('\t');
    if (namespace === undefined || key === undefined || extra.length > 0) throw validation('Each batch-get line must contain namespace and key separated by one tab');
    return { namespace: required(namespace, 'Namespace'), key: required(key, 'Key') };
  });
}

function parseSetEntries(value: string): CoreCacheSetRequest[] {
  try {
    const parsed = JSON.parse(value) as unknown;
    const result = batchSetRequestSchema.safeParse({ entries: parsed });
    if (!result.success) throw validation('Batch set JSON does not match the per-entry request contract');
    return result.data.entries;
  } catch (failure) {
    if (failure instanceof ManagementClientError) throw failure;
    throw validation('Batch set entries must be a valid JSON array');
  }
}

function lines(value: string) { const parsed = value.split(/\r?\n/u).filter((line) => line.trim() !== ''); if (parsed.length === 0 || parsed.length > 1_000) throw validation('Provide between 1 and 1000 lines'); return parsed; }
function required(value: string, label: string) { if (value.trim() === '') throw validation(`${label} is required`); return value; }
function positiveInt(value: string, maximum: number, label: string) { const parsed = Number(value); if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > maximum) throw validation(`${label} must be a positive integer`); return parsed; }
function validation(message: string) { return new ManagementClientError(400, 'VALIDATION_FAILED', message); }
function asError(value: unknown) { return value instanceof ManagementClientError ? value : new ManagementClientError(0, 'OPERATION_FAILED', 'Advanced operation failed'); }
function words(value: string) { return value.replaceAll(/([A-Z])/gu, ' $1').replace(/^./u, (character) => character.toUpperCase()); }
