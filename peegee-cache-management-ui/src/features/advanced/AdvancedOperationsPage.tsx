import { Alert, Button, Card, Checkbox, Col, Descriptions, Empty, Form, Input, Row, Space, Typography } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';

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
import { useManagementClients } from '../../store';
import { toQueryError, type ManagementQueryError } from '../../store/api/apiBase';

const { Title, Text, Paragraph } = Typography;

interface AdvancedOperationsPageProps {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly selectedSetupId?: string;
}

/**
 * Advanced operations: direct workflows over the complete backend service surface. Every call
 * here carries or returns sensitive material (values, owner tokens, exact metrics), so nothing on
 * this page goes through RTK Query: the no-store backend-capability client is used directly and
 * every result lives only in component state, cleared on visibility loss and unmount (design §8.2).
 */
export function AdvancedOperationsPage({ canOperate, canReveal, selectedSetupId }: AdvancedOperationsPageProps) {
  const { backendCapability: client } = useManagementClients();
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
  const [problem, setProblem] = useState<ManagementQueryError>();
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
      setProblem(asQueryError(failure));
    } finally {
      setBusy(false);
    }
  };

  if (selectedSetupId === undefined) {
    return <Workspace><Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Advanced operations unavailable</Title>}><Text type="secondary">Select a connected setup before using advanced operations.</Text></Empty></Card></Workspace>;
  }

  const checkExists = () => void run(async () => {
    setExists(await client.entryExists(selectedSetupId, encodeNamespace(required(namespace, 'Namespace')), encodeKey(required(key, 'Key'))));
  }, 'Existence check completed');

  const getMany = () => void run(async () => {
    setBatchGet(await client.batchGetEntries(selectedSetupId, { keys: parseKeys(batchKeys), reason: required(reason, 'Reason') }));
  }, 'Batch get completed');

  const setMany = () => void run(async () => {
    setBatchSet(await client.batchSetEntries(selectedSetupId, { entries: parseSetEntries(batchValues) }));
  }, 'Batch set completed');

  const deleteMany = () => void run(async () => {
    setBatchDelete(await client.batchDeleteEntries(selectedSetupId, { keys: parseKeys(batchDeleteKeys) }));
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

  const loadMetrics = () => void run(async () => { setMetrics(await client.cacheMetrics(selectedSetupId)); }, 'Core metrics refreshed');

  const acquire = () => void run(async () => {
    const result = await client.acquireLock(selectedSetupId, encodedLockNamespace(), encodedLockKey(), {
      ownerToken: required(ownerToken, 'Owner token'),
      leaseTtlMillis: positiveInt(leaseTtl, Number.MAX_SAFE_INTEGER, 'Lease TTL'),
      reentrantForSameOwner: reentrant,
      issueFencingToken: fencing,
    });
    setLockResult(result.acquired ? `Acquired · fencing ${result.fencingToken ?? 'not requested'} · expires ${result.leaseExpiresAt ?? 'unknown'}` : 'Not acquired');
  }, 'Lock acquisition completed');

  const renew = () => lockBoolean(() => client.renewLock(selectedSetupId, encodedLockNamespace(), encodedLockKey(), {
    ownerToken: required(ownerToken, 'Owner token'),
    leaseTtlMillis: positiveInt(leaseTtl, Number.MAX_SAFE_INTEGER, 'Lease TTL'),
  }), 'Renewed', 'Not renewed');
  const release = () => lockBoolean(() => client.releaseLock(selectedSetupId, encodedLockNamespace(), encodedLockKey(), required(ownerToken, 'Owner token')), 'Released by owner', 'Not released');
  const ownership = () => lockBoolean(() => client.isLockHeldBy(selectedSetupId, encodedLockNamespace(), encodedLockKey(), required(ownerToken, 'Owner token')), 'Owner token holds this lock', 'Owner token does not hold this lock');

  function encodedLockNamespace() { return encodeNamespace(required(lockNamespace, 'Lock namespace')); }
  function encodedLockKey() { return encodeKey(required(lockKey, 'Lock key')); }
  function lockBoolean(operation: () => Promise<boolean>, yes: string, no: string) {
    void run(async () => setLockResult(await operation() ? yes : no), 'Lock operation completed');
  }

  return (
    <Workspace>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Paragraph>Direct workflows for the complete public cache, scan, lock, and metrics service surface.</Paragraph>
        {problem !== undefined && <ProblemAlert problem={problem} />}
        {status !== '' && <Alert message={status} role="status" showIcon type="success" />}

        <Panel title="Existence and scan">
          <Form layout="vertical">
            <Row gutter={16}>
              <Col span={12}><Form.Item htmlFor="advanced-namespace" label="Namespace"><Input id="advanced-namespace" maxLength={128} onChange={(event) => setNamespace(event.target.value)} value={namespace} /></Form.Item></Col>
              <Col span={12}><Form.Item htmlFor="advanced-key" label="Key"><Input id="advanced-key" maxLength={1024} onChange={(event) => setKey(event.target.value)} value={key} /></Form.Item></Col>
            </Row>
            <Space className="workspace__actions" wrap><Button disabled={busy || namespace.trim() === '' || key.trim() === ''} onClick={checkExists} type="primary">Check entry existence</Button></Space>
            {exists !== undefined && <p><strong>Exists:</strong> {exists ? 'Yes' : 'No'}</p>}
            {canReveal && (
              <>
                <Row gutter={16} style={{ marginTop: 16 }}>
                  <Col span={12}><Form.Item htmlFor="scan-prefix" label="Scan key prefix (optional)"><Input id="scan-prefix" maxLength={1024} onChange={(event) => setScanPrefix(event.target.value)} value={scanPrefix} /></Form.Item></Col>
                  <Col span={12}><Form.Item htmlFor="scan-limit" label="Scan page size"><Input id="scan-limit" max={200} min={1} onChange={(event) => setScanLimit(event.target.value)} type="number" value={scanLimit} /></Form.Item></Col>
                </Row>
                <Space wrap>
                  <Checkbox checked={scanValues} onChange={(event) => setScanValues(event.target.checked)}>Include values</Checkbox>
                  <Checkbox checked={scanExpired} onChange={(event) => setScanExpired(event.target.checked)}>Include expired entries</Checkbox>
                </Space>
                <Space className="workspace__actions" style={{ marginTop: 16 }} wrap>
                  <Button disabled={busy || namespace.trim() === ''} onClick={() => scanEntries()} type="primary">Run backend scan</Button>
                  {scan?.hasMore === true && <Button disabled={busy} onClick={() => scanEntries(scan.nextCursor)}>Load next scan page</Button>}
                </Space>
                {scan !== undefined && <Result title="Scan result" value={scan} />}
              </>
            )}
          </Form>
        </Panel>

        {canReveal && (
          <Panel title="Batch get">
            <Form layout="vertical">
              <Paragraph>One entry per line: <code>namespace[TAB]key</code>.</Paragraph>
              <Form.Item htmlFor="batch-keys" label="Batch get keys"><Input.TextArea id="batch-keys" onChange={(event) => setBatchKeys(event.target.value)} rows={6} value={batchKeys} /></Form.Item>
              <Form.Item htmlFor="advanced-reason" label="Audit reason"><Input id="advanced-reason" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} value={reason} /></Form.Item>
              <Space className="workspace__actions" wrap><Button disabled={busy || batchKeys.trim() === ''} onClick={getMany} type="primary">Get entry batch</Button></Space>
              {batchGet !== undefined && <Result title="Batch get result" value={batchGet} />}
            </Form>
          </Panel>
        )}

        {canOperate && (
          <Panel title="Batch set">
            <Form layout="vertical">
              <Paragraph>Provide a JSON array. Every entry independently specifies namespace, key, typed value, TTL, set mode, expected version, and whether to return its previous value.</Paragraph>
              <Form.Item htmlFor="batch-values" label="Batch set entries (JSON)"><Input.TextArea id="batch-values" onChange={(event) => setBatchValues(event.target.value)} rows={6} value={batchValues} /></Form.Item>
              <Space className="workspace__actions" wrap><Button disabled={busy || batchValues.trim() === ''} onClick={setMany} type="primary">Set entry batch</Button></Space>
              {batchSet !== undefined && <Result title="Batch set result" value={batchSet} />}
            </Form>
          </Panel>
        )}

        {canOperate && (
          <Panel title="Cross-namespace batch delete">
            <Form layout="vertical">
              <Paragraph>One entry per line: <code>namespace[TAB]key</code>. This invokes the core cache service’s exact multi-key delete operation.</Paragraph>
              <Form.Item htmlFor="batch-delete-keys" label="Batch delete keys"><Input.TextArea id="batch-delete-keys" onChange={(event) => setBatchDeleteKeys(event.target.value)} rows={6} value={batchDeleteKeys} /></Form.Item>
              <Space className="workspace__actions" wrap><Button danger disabled={busy || batchDeleteKeys.trim() === ''} onClick={deleteMany}>Delete entry batch</Button></Space>
              {batchDelete !== undefined && <Result title="Batch delete result" value={batchDelete} />}
            </Form>
          </Panel>
        )}

        <Panel title="Exact core metrics">
          <Space className="workspace__actions" wrap><Button disabled={busy} onClick={loadMetrics} type="primary">Refresh core metrics</Button></Space>
          {metrics !== undefined && (
            <Descriptions column={3} size="small" style={{ marginTop: 16 }}>
              {Object.entries(metrics).map(([name, value]) => <Descriptions.Item key={name} label={words(name)}>{BigInt(value).toLocaleString('en-US')}</Descriptions.Item>)}
            </Descriptions>
          )}
        </Panel>

        {canOperate && (
          <Panel title="Owner lock lifecycle">
            <Form layout="vertical">
              <Row gutter={16}>
                <Col span={12}><Form.Item htmlFor="lock-namespace" label="Lock namespace"><Input id="lock-namespace" maxLength={128} onChange={(event) => setLockNamespace(event.target.value)} value={lockNamespace} /></Form.Item></Col>
                <Col span={12}><Form.Item htmlFor="lock-key" label="Lock key"><Input id="lock-key" maxLength={1024} onChange={(event) => setLockKey(event.target.value)} value={lockKey} /></Form.Item></Col>
                <Col span={12}><Form.Item htmlFor="lock-owner" label="Owner token"><Input.Password autoComplete="off" id="lock-owner" maxLength={4096} onChange={(event) => setOwnerToken(event.target.value)} value={ownerToken} visibilityToggle={false} /></Form.Item></Col>
                <Col span={12}><Form.Item htmlFor="lock-lease" label="Lease TTL milliseconds"><Input id="lock-lease" min={1} onChange={(event) => setLeaseTtl(event.target.value)} type="number" value={leaseTtl} /></Form.Item></Col>
              </Row>
              <Space wrap>
                <Checkbox checked={reentrant} onChange={(event) => setReentrant(event.target.checked)}>Reentrant for the same owner</Checkbox>
                <Checkbox checked={fencing} onChange={(event) => setFencing(event.target.checked)}>Issue fencing token</Checkbox>
              </Space>
              <Space className="workspace__actions" style={{ marginTop: 16 }} wrap>
                <Button disabled={busy} onClick={acquire} type="primary">Acquire lock</Button>
                <Button disabled={busy} onClick={renew}>Renew lock</Button>
                <Button disabled={busy} onClick={release}>Release by owner</Button>
                {canReveal && <Button disabled={busy} onClick={ownership}>Check ownership</Button>}
                <Button onClick={clearSensitive}>Clear sensitive state</Button>
              </Space>
              {lockResult !== '' && <p><strong>Lock result:</strong> {lockResult}</p>}
            </Form>
          </Panel>
        )}
      </Space>
    </Workspace>
  );
}

function Workspace({ children }: { readonly children: ReactNode }) {
  return (
    <section aria-labelledby="advanced-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary">Complete backend service exposure</Text>
        <Title id="advanced-title" level={1} style={{ marginTop: 4 }}>Advanced operations</Title>
      </div>
      {children}
    </section>
  );
}

function Panel({ children, title }: { readonly children: ReactNode; readonly title: string }) {
  const id = `advanced-${title.toLowerCase().replaceAll(' ', '-')}`;
  return (
    <section aria-labelledby={id} className="overview-panel">
      <Card title={<Title id={id} level={2} style={{ margin: 0, fontSize: 18 }}>{title}</Title>}>{children}</Card>
    </section>
  );
}

function Result({ title, value }: { readonly title: string; readonly value: unknown }) {
  return (
    <section aria-label={title} style={{ marginTop: 16 }}>
      <Title level={3} style={{ fontSize: 16 }}>{title}</Title>
      <pre className="value-content">{JSON.stringify(value, null, 2)}</pre>
    </section>
  );
}

function ProblemAlert({ problem }: { readonly problem: ManagementQueryError }) {
  return (
    <Alert
      description={<>{problem.message}{problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}</>}
      message={problem.code}
      role="alert"
      showIcon
      type="error"
    />
  );
}

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
function asQueryError(failure: unknown): ManagementQueryError {
  if (failure instanceof ManagementClientError) return toQueryError(failure);
  return { status: 0, code: 'OPERATION_FAILED', message: 'Advanced operation failed' };
}
function words(value: string) { return value.replaceAll(/([A-Z])/gu, ' $1').replace(/^./u, (character) => character.toUpperCase()); }
