import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert, Button, Card, Checkbox, Descriptions, Empty, Form, Input, InputNumber, List, Modal, Space, Table, Tag, Typography, type InputRef,
} from 'antd';
import { useRef, useState, type ReactNode } from 'react';

import type { BrowserSession } from '../../api/session-client';
import type { SetupConnectionRequest, SetupRegistrationRequest } from '../../api/setup-client';
import type { SetupCapabilities, SetupConnectionTest, SetupDetails, SetupHealth, SetupSummary } from '../../api/setup-schemas';
import { ValueSelect } from '../../components/common/ValueSelect';
import { formatDisplayBytes } from '../../presentation/display-bytes';
import { humanize } from '../../presentation/display-format';
import { formatDisplayInstant } from '../../presentation/display-time';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import {
  useConnectSetupMutation,
  useDetachSetupMutation,
  useForgetSetupMutation,
  useLazyGetSetupCapabilitiesQuery,
  useLazyGetSetupDetailsQuery,
  useLazyGetSetupHealthQuery,
  useListSetupsQuery,
  useRegisterSetupMutation,
  useTestRegisteredSetupMutation,
  useTestSetupConnectionMutation,
} from '../../store/api/setupsApi';

const { Title, Text } = Typography;

interface SetupsPageProps {
  readonly session: BrowserSession;
  readonly selectedSetupId?: string;
  readonly onSelectSetup: (setupId: string | undefined, capabilities?: SetupCapabilities) => void;
}

type ConfirmedAction = 'connect' | 'detach' | 'forget';
type SetupRuntimeConfiguration = NonNullable<SetupRegistrationRequest['runtime']>;

interface PendingAction {
  readonly action: ConfirmedAction;
  readonly setup: SetupSummary;
}

const initialRegistration: SetupRegistrationRequest = {
  setupId: '',
  displayName: '',
  host: '',
  port: 5432,
  database: '',
  schema: 'public',
  username: '',
  password: '',
  sslMode: 'VERIFY_FULL',
  trustProfileId: '',
  poolMaxSize: 10,
  runtime: {
    defaultTtlMillis: null,
    expirySweeperEnabled: false,
    expirySweepIntervalMillis: 30_000,
    expirySweepBatchSize: 500,
    writeBehindEnabled: false,
    writeBehindFlushIntervalMillis: 500,
    writeBehindMaxBufferSize: 10_000,
    writeBehindFlushBatchSize: 500,
    writeBehindMaxRetries: 3,
    writeBehindShutdownDrainTimeoutMillis: 5_000,
    pubSubChannelPrefix: 'peegee_cache',
    pubSubEnabled: true,
    schemaBootstrapMode: 'EXTERNAL',
    telemetryMode: 'NOOP',
  },
};

/**
 * Setups (reference: `peegeeq-management-ui/pages/DatabaseSetups.tsx` patterns — Table, Modal
 * forms, Descriptions details, confirm dialogs). Reads and lifecycle mutations go through RTK
 * Query; the registration password lives only in this component's form state and is cleared on
 * failure, never cached (design §8.2).
 */
export function SetupsPage({ session, selectedSetupId, onSelectSetup }: SetupsPageProps) {
  const setups = useListSetupsQuery();
  const [loadCapabilities] = useLazyGetSetupCapabilitiesQuery();
  const [loadDetails] = useLazyGetSetupDetailsQuery();
  const [loadHealth] = useLazyGetSetupHealthQuery();
  const [testConnection] = useTestSetupConnectionMutation();
  const [registerSetup] = useRegisterSetupMutation();
  const [testRegistered] = useTestRegisteredSetupMutation();
  const [connectSetup] = useConnectSetupMutation();
  const [detachSetup] = useDetachSetupMutation();
  const [forgetSetup] = useForgetSetupMutation();

  const [problem, setProblem] = useState<ManagementQueryError>();
  const [notice, setNotice] = useState<string>();
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [details, setDetails] = useState<{ details: SetupDetails; health: SetupHealth; capabilities: SetupCapabilities }>();
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [registration, setRegistration] = useState(initialRegistration);
  const [registrationBusy, setRegistrationBusy] = useState(false);
  const [registrationProblem, setRegistrationProblem] = useState<ManagementQueryError>();
  const [connectionTest, setConnectionTest] = useState<SetupConnectionTest>();
  const [pendingAction, setPendingAction] = useState<PendingAction>();
  const [actionBusy, setActionBusy] = useState(false);
  const [selectingSetupId, setSelectingSetupId] = useState<string>();
  const setupIdInput = useRef<InputRef | null>(null);

  const isOperator = session.roles.includes('operator');
  const canRegister = isOperator && session.features.setupRegistration;
  const rows = setups.data ?? [];
  const listProblem = queryError(setups.error);
  const loading = setups.isFetching;

  const updateRuntime = <K extends keyof SetupRuntimeConfiguration>(name: K, value: SetupRuntimeConfiguration[K]) =>
    setRegistration((current) => ({
      ...current,
      runtime: { ...(current.runtime ?? initialRegistration.runtime as SetupRuntimeConfiguration), [name]: value },
    }));

  const refresh = async () => {
    const result = await setups.refetch();
    if (result.data !== undefined && selectedSetupId !== undefined
      && !result.data.some((setup) => setup.setupId === selectedSetupId && setup.state === 'CONNECTED')) {
      onSelectSetup(undefined);
    }
  };

  const openDetails = async (setupId: string) => {
    setDetails(undefined);
    setDetailsOpen(true);
    setDetailsLoading(true);
    setProblem(undefined);
    try {
      const [loadedDetails, health, capabilities] = await Promise.all([
        loadDetails({ setupId }).unwrap(),
        loadHealth({ setupId }).unwrap(),
        loadCapabilities({ setupId }).unwrap(),
      ]);
      setDetails({ details: loadedDetails, health, capabilities });
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
      setDetailsOpen(false);
    } finally {
      setDetailsLoading(false);
    }
  };

  const selectSetup = async (setupId: string) => {
    setSelectingSetupId(setupId);
    setProblem(undefined);
    try {
      onSelectSetup(setupId, await loadCapabilities({ setupId }).unwrap());
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
    } finally {
      setSelectingSetupId(undefined);
    }
  };

  const retest = async (setup: SetupSummary) => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      const result = await testRegistered({ setupId: setup.setupId }).unwrap();
      setNotice(`${setup.displayName} responded in ${result.latencyMillis} ms; schema ${result.schemaState.toLowerCase()}.`);
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
    }
  };

  const executeAction = async () => {
    if (pendingAction === undefined) return;
    const { action, setup } = pendingAction;
    setActionBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    try {
      if (action === 'connect') await connectSetup({ setupId: setup.setupId }).unwrap();
      if (action === 'detach') await detachSetup({ setupId: setup.setupId }).unwrap();
      if (action === 'forget') await forgetSetup({ setupId: setup.setupId }).unwrap();
      if (action !== 'connect' && selectedSetupId === setup.setupId) onSelectSetup(undefined);
      setNotice(`${setup.displayName} was ${pastTense(action)}.`);
      setPendingAction(undefined);
    } catch (failure: unknown) {
      setProblem(asQueryError(failure));
    } finally {
      setActionBusy(false);
    }
  };

  const closeRegistration = () => {
    setRegistrationOpen(false);
    setRegistration(initialRegistration);
    setConnectionTest(undefined);
    setRegistrationProblem(undefined);
  };

  const connectionRequest = (): SetupConnectionRequest => ({
    host: registration.host,
    port: registration.port,
    database: registration.database,
    schema: registration.schema,
    username: registration.username,
    password: registration.password,
    sslMode: registration.sslMode,
    trustProfileId: registration.trustProfileId,
    poolMaxSize: registration.poolMaxSize,
    runtime: registration.runtime,
  });

  const testRegistration = async () => {
    setRegistrationBusy(true);
    setRegistrationProblem(undefined);
    setConnectionTest(undefined);
    try {
      setConnectionTest(await testConnection(connectionRequest()).unwrap());
    } catch (failure: unknown) {
      setRegistrationProblem(asQueryError(failure));
    } finally {
      setRegistrationBusy(false);
    }
  };

  const register = async () => {
    setRegistrationBusy(true);
    setRegistrationProblem(undefined);
    try {
      const created = await registerSetup(registration).unwrap();
      closeRegistration();
      onSelectSetup(created.setupId, await loadCapabilities({ setupId: created.setupId }).unwrap());
      setNotice(`${created.displayName} was registered and selected.`);
    } catch (failure: unknown) {
      setRegistrationProblem(asQueryError(failure));
      setRegistration((current) => ({ ...current, password: '' }));
    } finally {
      setRegistrationBusy(false);
    }
  };

  const openRegistration = () => setRegistrationOpen(true);
  const shownProblem = problem ?? listProblem;

  return (
    <section aria-labelledby="workspace-title" className="workspace setups-workspace">
      <div className="workspace__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Text className="workspace__context" type="secondary">Database connections and runtime scope</Text>
          <Title id="workspace-title" level={1} style={{ marginTop: 4 }}>Setups</Title>
          <Text>Register, verify, connect, and safely detach PeeGeeQ Cache database setups.</Text>
        </div>
        <Space className="workspace__actions">
          <Button disabled={loading} icon={<ReloadOutlined aria-hidden="true" />} onClick={() => void refresh()}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </Button>
          {canRegister && (
            <Button icon={<PlusOutlined aria-hidden="true" />} onClick={openRegistration} type="primary">Register setup</Button>
          )}
        </Space>
      </div>

      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {!isOperator && <Alert message="Viewer access is read-only. An operator can manage setup lifecycles." showIcon type="info" />}
        {isOperator && !session.features.setupRegistration && <Alert message="Setup registration is disabled by this management server." showIcon type="info" />}
        {notice !== undefined && <Alert message={notice} role="status" showIcon type="success" />}
        {shownProblem !== undefined && <ProblemAlert problem={shownProblem} />}

        {setups.isLoading ? (
          <Text aria-busy="true">Loading registered setups…</Text>
        ) : rows.length === 0 && listProblem === undefined ? (
          <Card>
            <Empty description={<Title level={2} style={{ fontSize: 18 }}>No setups registered</Title>}>
              <Text type="secondary">Register a TLS-verified PostgreSQL connection to begin managing cache data.</Text>
              {canRegister && (
                <div style={{ marginTop: 16 }}>
                  <Button onClick={openRegistration} type="primary">Register the first setup</Button>
                </div>
              )}
            </Empty>
          </Card>
        ) : rows.length > 0 ? (
          <div aria-label="Registered setups" className="table-scroll" role="region" tabIndex={0}>
            <Table
              columns={[
                {
                  title: 'Setup', key: 'setup',
                  render: (_value: unknown, setup: SetupSummary) => (
                    <><strong>{setup.displayName}</strong><br /><Text type="secondary">{setup.setupId} · {setup.source === 'CONFIGURED' ? 'Configured' : 'Session'}</Text></>
                  ),
                },
                {
                  title: 'Database', key: 'database',
                  render: (_value: unknown, setup: SetupSummary) => (
                    <>{setup.database}<br /><Text type="secondary">{setup.host}:{setup.port}/{setup.schema}</Text></>
                  ),
                },
                { title: 'State', key: 'state', render: (_value: unknown, setup: SetupSummary) => <StateTag value={setup.state} /> },
                {
                  title: 'Health', key: 'health',
                  render: (_value: unknown, setup: SetupSummary) => setup.lastHealth === null
                    ? <Text type="secondary">Not checked</Text>
                    : <><StateTag value={setup.lastHealth.status} /><Text type="secondary">{setup.lastHealth.latencyMillis} ms</Text></>,
                },
                {
                  title: 'Scope', key: 'scope',
                  render: (_value: unknown, setup: SetupSummary) => {
                    const selected = selectedSetupId === setup.setupId;
                    return (
                      <Button
                        disabled={setup.state !== 'CONNECTED' || selected || selectingSetupId !== undefined}
                        onClick={() => void selectSetup(setup.setupId)}
                        size="small"
                        type="link"
                      >
                        {selected ? 'Selected' : selectingSetupId === setup.setupId ? 'Selecting…' : 'Use setup'}
                      </Button>
                    );
                  },
                },
                {
                  title: <span className="sr-only">Actions</span>, key: 'actions',
                  render: (_value: unknown, setup: SetupSummary) => (
                    <Space className="row-actions" size="small" wrap>
                      <Button onClick={() => void openDetails(setup.setupId)} size="small" type="link">Details</Button>
                      {isOperator && (
                        <>
                          <Button onClick={() => void retest(setup)} size="small" type="link">Test</Button>
                          {setup.state === 'DETACHED'
                            ? <Button onClick={() => setPendingAction({ action: 'connect', setup })} size="small" type="link">Connect</Button>
                            : <Button onClick={() => setPendingAction({ action: 'detach', setup })} size="small" type="link">Detach</Button>}
                          {setup.source === 'UI_SESSION' && (
                            <Button danger onClick={() => setPendingAction({ action: 'forget', setup })} size="small" type="link">Forget</Button>
                          )}
                        </>
                      )}
                    </Space>
                  ),
                },
              ]}
              dataSource={rows.map((setup) => ({ ...setup, key: setup.setupId }))}
              pagination={false}
              rowClassName={(setup) => (selectedSetupId === setup.setupId ? 'setup-row--selected' : '')}
              size="middle"
            />
          </div>
        ) : null}
      </Space>

      <Modal
        afterOpenChange={(open) => { if (open) setupIdInput.current?.focus(); }}
        closable={false}
        destroyOnHidden
        footer={null}
        maskClosable={!registrationBusy}
        onCancel={registrationBusy ? undefined : closeRegistration}
        open={registrationOpen}
        title={<Title id="registration-title" level={2} style={{ margin: 0, fontSize: 20 }}>Register setup</Title>}
        width={880}
      >
        <div className="modal__heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <Text className="workspace__context" type="secondary">TLS-verified PostgreSQL</Text>
          <Button aria-label="Close registration" disabled={registrationBusy} onClick={closeRegistration} size="small">Close</Button>
        </div>
        <Form layout="vertical" onFinish={() => void register()}>
          <div className="form-grid">
            <Form.Item htmlFor="setup-id" label="Setup ID"><Input id="setup-id" maxLength={63} onChange={(event) => setRegistration({ ...registration, setupId: event.target.value })} pattern="[a-z][a-z0-9\-]{0,62}" ref={setupIdInput} required value={registration.setupId} /></Form.Item>
            <Form.Item htmlFor="setup-display-name" label="Display name"><Input id="setup-display-name" maxLength={128} onChange={(event) => setRegistration({ ...registration, displayName: event.target.value })} required value={registration.displayName} /></Form.Item>
            <Form.Item htmlFor="setup-host" label="Host"><Input id="setup-host" maxLength={253} onChange={(event) => setRegistration({ ...registration, host: event.target.value })} required value={registration.host} /></Form.Item>
            <Form.Item htmlFor="setup-port" label="Port"><InputNumber id="setup-port" max={65_535} min={1} onChange={(value) => setRegistration({ ...registration, port: Number(value ?? 0) })} required style={{ width: '100%' }} value={registration.port} /></Form.Item>
            <Form.Item htmlFor="setup-database" label="Database"><Input id="setup-database" maxLength={63} onChange={(event) => setRegistration({ ...registration, database: event.target.value })} required value={registration.database} /></Form.Item>
            <Form.Item htmlFor="setup-schema" label="Schema"><Input id="setup-schema" maxLength={63} onChange={(event) => setRegistration({ ...registration, schema: event.target.value })} required value={registration.schema} /></Form.Item>
            <Form.Item htmlFor="setup-username" label="Username"><Input autoComplete="username" id="setup-username" maxLength={128} onChange={(event) => setRegistration({ ...registration, username: event.target.value })} required value={registration.username} /></Form.Item>
            <Form.Item htmlFor="setup-password" label="Password"><Input.Password autoComplete="new-password" id="setup-password" maxLength={4_096} onChange={(event) => setRegistration({ ...registration, password: event.target.value })} required value={registration.password} /></Form.Item>
            <Form.Item htmlFor="setup-trust-profile" label="Trust profile"><Input id="setup-trust-profile" maxLength={128} onChange={(event) => setRegistration({ ...registration, trustProfileId: event.target.value })} required value={registration.trustProfileId} /></Form.Item>
            <Form.Item htmlFor="setup-pool-size" label="Pool size"><InputNumber id="setup-pool-size" max={100} min={1} onChange={(value) => setRegistration({ ...registration, poolMaxSize: Number(value ?? 0) })} required style={{ width: '100%' }} value={registration.poolMaxSize} /></Form.Item>
            <Form.Item htmlFor="setup-tls-mode" label="TLS mode"><Input disabled id="setup-tls-mode" value="VERIFY_FULL" /></Form.Item>
          </div>
          <Card className="details-section" size="small" title="Runtime behavior">
            <Text type="secondary">These settings are applied when this setup connects.</Text>
            <div className="form-grid" style={{ marginTop: 12 }}>
              <Form.Item htmlFor="runtime-default-ttl" label="Default TTL milliseconds"><InputNumber id="runtime-default-ttl" min={1} onChange={(value) => updateRuntime('defaultTtlMillis', value === null || value === undefined ? null : Number(value))} placeholder="Persistent by default" style={{ width: '100%' }} value={registration.runtime?.defaultTtlMillis ?? null} /></Form.Item>
              <Form.Item htmlFor="runtime-sweep-interval" label="Expiry sweep interval milliseconds"><InputNumber id="runtime-sweep-interval" min={1} onChange={(value) => updateRuntime('expirySweepIntervalMillis', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.expirySweepIntervalMillis} /></Form.Item>
              <Form.Item htmlFor="runtime-sweep-batch" label="Expiry sweep batch size"><InputNumber id="runtime-sweep-batch" min={1} onChange={(value) => updateRuntime('expirySweepBatchSize', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.expirySweepBatchSize} /></Form.Item>
              <Form.Item htmlFor="runtime-wb-interval" label="Write-behind flush interval milliseconds"><InputNumber id="runtime-wb-interval" min={1} onChange={(value) => updateRuntime('writeBehindFlushIntervalMillis', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.writeBehindFlushIntervalMillis} /></Form.Item>
              <Form.Item htmlFor="runtime-wb-buffer" label="Write-behind maximum buffer"><InputNumber id="runtime-wb-buffer" min={100} onChange={(value) => updateRuntime('writeBehindMaxBufferSize', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.writeBehindMaxBufferSize} /></Form.Item>
              <Form.Item htmlFor="runtime-wb-batch" label="Write-behind flush batch size"><InputNumber id="runtime-wb-batch" min={1} onChange={(value) => updateRuntime('writeBehindFlushBatchSize', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.writeBehindFlushBatchSize} /></Form.Item>
              <Form.Item htmlFor="runtime-wb-retries" label="Write-behind maximum retries"><InputNumber id="runtime-wb-retries" min={0} onChange={(value) => updateRuntime('writeBehindMaxRetries', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.writeBehindMaxRetries} /></Form.Item>
              <Form.Item htmlFor="runtime-drain-timeout" label="Shutdown drain timeout milliseconds"><InputNumber id="runtime-drain-timeout" min={1} onChange={(value) => updateRuntime('writeBehindShutdownDrainTimeoutMillis', Number(value ?? 0))} required style={{ width: '100%' }} value={registration.runtime?.writeBehindShutdownDrainTimeoutMillis} /></Form.Item>
              <Form.Item htmlFor="runtime-pubsub-prefix" label="Pub/Sub channel prefix"><Input id="runtime-pubsub-prefix" maxLength={48} onChange={(event) => updateRuntime('pubSubChannelPrefix', event.target.value)} required value={registration.runtime?.pubSubChannelPrefix} /></Form.Item>
              <Form.Item htmlFor="schema-bootstrap-mode" label="Schema bootstrap">
                <ValueSelect<'EXTERNAL' | 'APPLY'>
                  id="schema-bootstrap-mode"
                  onChange={(value) => updateRuntime('schemaBootstrapMode', value)}
                  options={[{ value: 'EXTERNAL', label: 'Provisioned externally' }, { value: 'APPLY', label: 'Apply bundled schema at startup' }]}
                  value={registration.runtime?.schemaBootstrapMode}
                />
              </Form.Item>
              <Form.Item htmlFor="runtime-telemetry" label="Telemetry adapter"><Input disabled id="runtime-telemetry" value="Built-in metrics (vendor-neutral exporter disabled)" /></Form.Item>
            </div>
            <Space wrap>
              <Checkbox checked={registration.runtime?.expirySweeperEnabled ?? false} onChange={(event) => updateRuntime('expirySweeperEnabled', event.target.checked)}>Run the expiry sweeper</Checkbox>
              <Checkbox checked={registration.runtime?.writeBehindEnabled ?? false} onChange={(event) => updateRuntime('writeBehindEnabled', event.target.checked)}>Enable write-behind buffering</Checkbox>
              <Checkbox checked={registration.runtime?.pubSubEnabled ?? false} onChange={(event) => updateRuntime('pubSubEnabled', event.target.checked)}>Enable Pub/Sub</Checkbox>
            </Space>
          </Card>
          {connectionTest !== undefined && (
            <Alert
              message={`Connection succeeded in ${connectionTest.latencyMillis} ms; schema ${connectionTest.schemaState.toLowerCase()}.`}
              role="status"
              showIcon
              style={{ marginTop: 16 }}
              type="success"
            />
          )}
          {registrationProblem !== undefined && <div style={{ marginTop: 16 }}><ProblemAlert problem={registrationProblem} /></div>}
          <Space className="modal__actions" style={{ marginTop: 16, justifyContent: 'flex-end', width: '100%' }}>
            <Button disabled={registrationBusy} onClick={() => void testRegistration()}>Test connection</Button>
            <Button disabled={registrationBusy} htmlType="submit" type="primary">{registrationBusy ? 'Working…' : 'Register setup'}</Button>
          </Space>
        </Form>
      </Modal>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        onCancel={() => setDetailsOpen(false)}
        open={detailsOpen}
        title={<Title id="details-title" level={2} style={{ margin: 0, fontSize: 20 }}>Setup details</Title>}
        width={760}
      >
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
          <Button aria-label="Close details" onClick={() => setDetailsOpen(false)} size="small">Close</Button>
        </div>
        {detailsLoading && <Text aria-busy="true">Loading details…</Text>}
        {details !== undefined && <SetupDetailsView capabilities={details.capabilities} details={details.details} health={details.health} />}
      </Modal>

      <Modal
        closable={false}
        destroyOnHidden
        footer={null}
        maskClosable={!actionBusy}
        onCancel={actionBusy ? undefined : () => setPendingAction(undefined)}
        open={pendingAction !== undefined}
        title={pendingAction === undefined ? '' : <Title id="action-title" level={2} style={{ margin: 0, fontSize: 20 }}>{actionTitle(pendingAction.action)} {pendingAction.setup.displayName}?</Title>}
      >
        {pendingAction !== undefined && (
          <>
            <Text>{actionDescription(pendingAction.action)}</Text>
            <Space className="modal__actions" style={{ marginTop: 16, justifyContent: 'flex-end', width: '100%' }}>
              <Button disabled={actionBusy} onClick={() => setPendingAction(undefined)}>Cancel</Button>
              <Button danger={pendingAction.action === 'forget'} disabled={actionBusy} onClick={() => void executeAction()} type="primary">
                {actionBusy ? 'Working…' : actionTitle(pendingAction.action)}
              </Button>
            </Space>
          </>
        )}
      </Modal>
    </section>
  );
}

function StateTag({ value }: { readonly value: string }) {
  const normalized = value.toLowerCase();
  const color = normalized === 'connected' || normalized === 'up' || normalized === 'ready' || normalized === 'available'
    ? 'green'
    : normalized === 'detached' || normalized === 'down' || normalized === 'unhealthy' || normalized === 'unavailable'
      ? 'red'
      : 'gold';
  return <Tag className={`badge badge--${color}`} color={color}>{humanize(value)}</Tag>;
}

function SetupDetailsView({ details, health, capabilities }: {
  readonly details: SetupDetails;
  readonly health: SetupHealth;
  readonly capabilities: SetupCapabilities;
}) {
  const rows: Array<[string, ReactNode]> = [
    ['Setup ID', details.setup.setupId],
    ['Host', `${details.setup.host}:${details.setup.port}`],
    ['Database', details.setup.database],
    ['Schema', details.setup.schema],
    ['Migration', details.migrationVersion],
    ['Pool size', String(details.runtime.poolMaxSize)],
    ['Default TTL', details.runtime.defaultTtlMillis === null ? 'Persistent' : `${details.runtime.defaultTtlMillis} ms`],
    ['Expiry sweeper', details.runtime.expirySweeperEnabled ? `Every ${details.runtime.expirySweepIntervalMillis} ms · ${details.runtime.expirySweepBatchSize} rows` : 'Disabled'],
    ['Write-behind', details.runtime.writeBehindEnabled ? `Every ${details.runtime.writeBehindFlushIntervalMillis} ms · buffer ${details.runtime.writeBehindMaxBufferSize}` : 'Disabled'],
    ['Pub/Sub', details.runtime.pubSubEnabled ? `Enabled · prefix ${details.runtime.pubSubChannelPrefix}` : 'Disabled'],
    ['Schema bootstrap', details.runtime.schemaBootstrapMode === 'APPLY' ? 'Apply at startup' : 'External'],
    ['Telemetry adapter', details.runtime.telemetryMode],
    ['Registered', formatDisplayInstant(details.registeredAt)],
    ['Connected', details.connectedAt === null ? 'Not connected' : formatDisplayInstant(details.connectedAt)],
  ];
  return (
    <div className="setup-details">
      <Descriptions column={2} size="small">
        {rows.map(([label, value]) => <Descriptions.Item key={label} label={label}>{value}</Descriptions.Item>)}
      </Descriptions>
      <section aria-labelledby="health-title" className="details-section">
        <Title id="health-title" level={3} style={{ fontSize: 16 }}>Database health</Title>
        <Space>
          <StateTag value={health.status} />
          <Text>{health.latencyMillis} ms · {health.schemaReady ? 'Schema ready' : 'Schema unavailable'}</Text>
        </Space>
        <p><Text>{health.detail}</Text></p>
        <Text type="secondary">Checked {formatDisplayInstant(health.checkedAt)}</Text>
      </section>
      <section aria-labelledby="capabilities-title" className="details-section">
        <Title id="capabilities-title" level={3} style={{ fontSize: 16 }}>Capabilities</Title>
        <List
          className="capability-list"
          dataSource={Object.entries(capabilities.capabilities)}
          grid={{ gutter: 8, column: 2 }}
          renderItem={([name, available]) => (
            <List.Item key={name} style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span>{humanizeCapability(name)}</span>
              <StateTag value={available ? 'AVAILABLE' : 'UNAVAILABLE'} />
            </List.Item>
          )}
          size="small"
        />
        <Descriptions column={3} size="small">
          <Descriptions.Item label="Maximum value">{formatDisplayBytes(capabilities.limits.maximumValueBytes)}</Descriptions.Item>
          <Descriptions.Item label="Pub/Sub payload">{formatDisplayBytes(capabilities.limits.pubSubPayloadMaxBytes)}</Descriptions.Item>
          <Descriptions.Item label="Pub/Sub channel">{formatDisplayBytes(capabilities.limits.pubSubChannelMaxBytes)}</Descriptions.Item>
        </Descriptions>
      </section>
    </div>
  );
}

function ProblemAlert({ problem }: { readonly problem: ManagementQueryError }) {
  return (
    <Alert
      description={(
        <>
          <p>{problem.message}</p>
          {problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}
        </>
      )}
      message={problem.code}
      role="alert"
      showIcon
      type="error"
    />
  );
}

function queryError(error: unknown): ManagementQueryError | undefined {
  return isManagementQueryError(error) ? error : undefined;
}

function asQueryError(failure: unknown): ManagementQueryError {
  return isManagementQueryError(failure)
    ? failure
    : { status: 0, code: 'CONNECTION_FAILED', message: 'The setup request could not be completed' };
}

function humanizeCapability(value: string): string {
  return value.replace(/([a-z])([A-Z])/gu, '$1 $2').toLowerCase().replace(/^./u, (letter) => letter.toUpperCase());
}

function actionTitle(action: ConfirmedAction): string {
  return action === 'connect' ? 'Connect' : action === 'detach' ? 'Detach' : 'Forget';
}

function actionDescription(action: ConfirmedAction): string {
  if (action === 'connect') return 'The server will create a new runtime connection using the registered secret reference.';
  if (action === 'detach') return 'The runtime connection will close, but its registration and database data will remain.';
  return 'The session-owned registration and its stored connection secret will be removed. Database data is not deleted.';
}

function pastTense(action: ConfirmedAction): string {
  return action === 'connect' ? 'connected' : action === 'detach' ? 'detached' : 'forgotten';
}
