import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';

import type { BrowserSession } from '../../api/session-client';
import { ManagementClientError } from '../../api/session-client';
import type {
  SetupClientPort,
  SetupConnectionRequest,
  SetupRegistrationRequest,
} from '../../api/setup-client';
import type {
  SetupConnectionTest,
  SetupCapabilities,
  SetupDetails,
  SetupHealth,
  SetupSummary,
} from '../../api/setup-schemas';
import { formatDisplayInstant } from '../../presentation/display-time';

interface SetupsPageProps {
  readonly client: SetupClientPort;
  readonly session: BrowserSession;
  readonly selectedSetupId?: string;
  readonly onSelectSetup: (
    setupId: string | undefined,
    capabilities?: SetupCapabilities,
  ) => void;
}

type ConfirmedAction = 'connect' | 'detach' | 'forget';

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
};

export function SetupsPage({
  client,
  session,
  selectedSetupId,
  onSelectSetup,
}: SetupsPageProps) {
  const [setups, setSetups] = useState<SetupSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<ManagementClientError>();
  const [notice, setNotice] = useState<string>();
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [details, setDetails] = useState<SetupDetails>();
  const [detailsHealth, setDetailsHealth] = useState<SetupHealth>();
  const [detailsCapabilities, setDetailsCapabilities] = useState<SetupCapabilities>();
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [registration, setRegistration] = useState(initialRegistration);
  const [registrationBusy, setRegistrationBusy] = useState(false);
  const [registrationProblem, setRegistrationProblem] = useState<ManagementClientError>();
  const [connectionTest, setConnectionTest] = useState<SetupConnectionTest>();
  const [pendingAction, setPendingAction] = useState<PendingAction>();
  const [actionBusy, setActionBusy] = useState(false);
  const [selectingSetupId, setSelectingSetupId] = useState<string>();
  const isOperator = session.roles.includes('operator');
  const canRegister = isOperator && session.features.setupRegistration;

  const load = useCallback(async () => {
    setLoading(true);
    setProblem(undefined);
    try {
      const loaded = await client.list();
      setSetups(loaded);
      if (selectedSetupId !== undefined
        && !loaded.some((setup) => setup.setupId === selectedSetupId && setup.state === 'CONNECTED')) {
        onSelectSetup(undefined);
      }
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setLoading(false);
    }
  }, [client, onSelectSetup, selectedSetupId]);

  useEffect(() => {
    let active = true;
    void client.list()
      .then((loaded) => {
        if (!active) return;
        setSetups(loaded);
        setProblem(undefined);
      })
      .catch((failure: unknown) => {
        if (active) setProblem(asClientError(failure));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [client]);

  const openDetails = async (setupId: string) => {
    setDetails(undefined);
    setDetailsHealth(undefined);
    setDetailsCapabilities(undefined);
    setDetailsOpen(true);
    setDetailsLoading(true);
    setProblem(undefined);
    try {
      const [loadedDetails, health, capabilities] = await Promise.all([
        client.details(setupId),
        client.health(setupId),
        client.capabilities(setupId),
      ]);
      setDetails(loadedDetails);
      setDetailsHealth(health);
      setDetailsCapabilities(capabilities);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
      setDetailsOpen(false);
    } finally {
      setDetailsLoading(false);
    }
  };

  const selectSetup = async (setupId: string) => {
    setSelectingSetupId(setupId);
    setProblem(undefined);
    try {
      onSelectSetup(setupId, await client.capabilities(setupId));
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setSelectingSetupId(undefined);
    }
  };

  const testRegistered = async (setup: SetupSummary) => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      const result = await client.testRegistered(setup.setupId);
      setNotice(
        `${setup.displayName} responded in ${result.latencyMillis} ms; schema ${result.schemaState.toLowerCase()}.`,
      );
      await load();
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    }
  };

  const executeAction = async () => {
    if (pendingAction === undefined) return;
    const { action, setup } = pendingAction;
    setActionBusy(true);
    setProblem(undefined);
    setNotice(undefined);
    try {
      if (action === 'connect') await client.connect(setup.setupId);
      if (action === 'detach') await client.detach(setup.setupId);
      if (action === 'forget') await client.forget(setup.setupId);
      if (action !== 'connect' && selectedSetupId === setup.setupId) onSelectSetup(undefined);
      setNotice(`${setup.displayName} was ${pastTense(action)}.`);
      setPendingAction(undefined);
      await load();
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
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
  });

  const testRegistration = async () => {
    setRegistrationBusy(true);
    setRegistrationProblem(undefined);
    setConnectionTest(undefined);
    try {
      setConnectionTest(await client.testConnection(connectionRequest()));
    } catch (failure: unknown) {
      setRegistrationProblem(asClientError(failure));
    } finally {
      setRegistrationBusy(false);
    }
  };

  const register = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setRegistrationBusy(true);
    setRegistrationProblem(undefined);
    try {
      const created = await client.register(registration);
      closeRegistration();
      onSelectSetup(created.setupId, await client.capabilities(created.setupId));
      setNotice(`${created.displayName} was registered and selected.`);
      await load();
    } catch (failure: unknown) {
      setRegistrationProblem(asClientError(failure));
      setRegistration((current) => ({ ...current, password: '' }));
    } finally {
      setRegistrationBusy(false);
    }
  };

  return (
    <section className="workspace setups-workspace" aria-labelledby="workspace-title">
      <div className="workspace__heading">
        <div>
          <p className="workspace__context">Database connections and runtime scope</p>
          <h1 id="workspace-title">Setups</h1>
          <p>Register, verify, connect, and safely detach PeeGeeQ Cache database setups.</p>
        </div>
        <div className="workspace__actions">
          <button className="button button--secondary" disabled={loading} onClick={() => void load()} type="button">
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
          {canRegister && (
            <button className="button" onClick={() => setRegistrationOpen(true)} type="button">
              Register setup
            </button>
          )}
        </div>
      </div>

      {!isOperator && (
        <p className="callout">Viewer access is read-only. An operator can manage setup lifecycles.</p>
      )}
      {isOperator && !session.features.setupRegistration && (
        <p className="callout">Setup registration is disabled by this management server.</p>
      )}
      {notice !== undefined && <p className="notice" role="status">{notice}</p>}
      {problem !== undefined && <ProblemAlert problem={problem} />}

      {loading && setups.length === 0 ? (
        <div className="empty-state" aria-busy="true">Loading registered setups…</div>
      ) : setups.length === 0 && problem === undefined ? (
        <div className="empty-state">
          <h2>No setups registered</h2>
          <p>Register a TLS-verified PostgreSQL connection to begin managing cache data.</p>
          {canRegister && (
            <button className="button" onClick={() => setRegistrationOpen(true)} type="button">
              Register the first setup
            </button>
          )}
        </div>
      ) : setups.length > 0 ? (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Setup</th>
                <th scope="col">Database</th>
                <th scope="col">State</th>
                <th scope="col">Health</th>
                <th scope="col">Scope</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {setups.map((setup) => {
                const selected = selectedSetupId === setup.setupId;
                return (
                  <tr data-selected={selected || undefined} key={setup.setupId}>
                    <td>
                      <strong>{setup.displayName}</strong>
                      <small>{setup.setupId} · {setup.source === 'CONFIGURED' ? 'Configured' : 'Session'}</small>
                    </td>
                    <td>
                      {setup.database}
                      <small>{setup.host}:{setup.port}/{setup.schema}</small>
                    </td>
                    <td><StateBadge value={setup.state} /></td>
                    <td>
                      {setup.lastHealth === null
                        ? <span className="muted">Not checked</span>
                        : <><StateBadge value={setup.lastHealth.status} /><small>{setup.lastHealth.latencyMillis} ms</small></>}
                    </td>
                    <td>
                      <button
                        className="button button--quiet"
                        disabled={setup.state !== 'CONNECTED' || selected || selectingSetupId !== undefined}
                        onClick={() => void selectSetup(setup.setupId)}
                        type="button"
                      >
                        {selected ? 'Selected' : selectingSetupId === setup.setupId ? 'Selecting…' : 'Use setup'}
                      </button>
                    </td>
                    <td>
                      <div className="row-actions">
                        <button className="button button--quiet" onClick={() => void openDetails(setup.setupId)} type="button">
                          Details
                        </button>
                        {isOperator && (
                          <>
                            <button className="button button--quiet" onClick={() => void testRegistered(setup)} type="button">
                              Test
                            </button>
                            {setup.state === 'DETACHED' ? (
                              <button className="button button--quiet" onClick={() => setPendingAction({ action: 'connect', setup })} type="button">
                                Connect
                              </button>
                            ) : (
                              <button className="button button--quiet" onClick={() => setPendingAction({ action: 'detach', setup })} type="button">
                                Detach
                              </button>
                            )}
                            {setup.source === 'UI_SESSION' && (
                              <button className="button button--danger" onClick={() => setPendingAction({ action: 'forget', setup })} type="button">
                                Forget
                              </button>
                            )}
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {registrationOpen && (
        <div className="modal-backdrop">
          <section aria-labelledby="registration-title" aria-modal="true" className="modal" role="dialog">
            <div className="modal__heading">
              <div>
                <p className="workspace__context">TLS-verified PostgreSQL</p>
                <h2 id="registration-title">Register setup</h2>
              </div>
              <button aria-label="Close registration" className="icon-button" disabled={registrationBusy} onClick={closeRegistration} type="button">Close</button>
            </div>
            <form onSubmit={(event) => void register(event)}>
              <div className="form-grid">
                <Field label="Setup ID"><input maxLength={63} pattern="[a-z][a-z0-9\-]{0,62}" required value={registration.setupId} onChange={(event) => setRegistration({ ...registration, setupId: event.target.value })} /></Field>
                <Field label="Display name"><input maxLength={128} required value={registration.displayName} onChange={(event) => setRegistration({ ...registration, displayName: event.target.value })} /></Field>
                <Field label="Host"><input maxLength={253} required value={registration.host} onChange={(event) => setRegistration({ ...registration, host: event.target.value })} /></Field>
                <Field label="Port"><input max={65_535} min={1} required type="number" value={registration.port} onChange={(event) => setRegistration({ ...registration, port: Number(event.target.value) })} /></Field>
                <Field label="Database"><input maxLength={63} required value={registration.database} onChange={(event) => setRegistration({ ...registration, database: event.target.value })} /></Field>
                <Field label="Schema"><input maxLength={63} required value={registration.schema} onChange={(event) => setRegistration({ ...registration, schema: event.target.value })} /></Field>
                <Field label="Username"><input autoComplete="username" maxLength={128} required value={registration.username} onChange={(event) => setRegistration({ ...registration, username: event.target.value })} /></Field>
                <Field label="Password"><input autoComplete="new-password" maxLength={4_096} required type="password" value={registration.password} onChange={(event) => setRegistration({ ...registration, password: event.target.value })} /></Field>
                <Field label="Trust profile"><input maxLength={128} required value={registration.trustProfileId} onChange={(event) => setRegistration({ ...registration, trustProfileId: event.target.value })} /></Field>
                <Field label="Pool size"><input max={100} min={1} required type="number" value={registration.poolMaxSize} onChange={(event) => setRegistration({ ...registration, poolMaxSize: Number(event.target.value) })} /></Field>
                <Field label="TLS mode"><input disabled value="VERIFY_FULL" /></Field>
              </div>
              {connectionTest !== undefined && (
                <p className="notice" role="status">
                  Connection succeeded in {connectionTest.latencyMillis} ms; schema {connectionTest.schemaState.toLowerCase()}.
                </p>
              )}
              {registrationProblem !== undefined && <ProblemAlert problem={registrationProblem} />}
              <div className="modal__actions">
                <button className="button button--secondary" disabled={registrationBusy} onClick={() => void testRegistration()} type="button">Test connection</button>
                <button className="button" disabled={registrationBusy} type="submit">{registrationBusy ? 'Working…' : 'Register setup'}</button>
              </div>
            </form>
          </section>
        </div>
      )}

      {detailsOpen && (
        <div className="modal-backdrop">
          <section aria-labelledby="details-title" aria-modal="true" className="modal modal--compact" role="dialog">
            <div className="modal__heading">
              <h2 id="details-title">Setup details</h2>
              <button aria-label="Close details" className="icon-button" onClick={() => setDetailsOpen(false)} type="button">Close</button>
            </div>
            {detailsLoading && <p aria-busy="true">Loading details…</p>}
            {details !== undefined && detailsHealth !== undefined && detailsCapabilities !== undefined && (
              <SetupDetailsView
                capabilities={detailsCapabilities}
                details={details}
                health={detailsHealth}
              />
            )}
          </section>
        </div>
      )}

      {pendingAction !== undefined && (
        <div className="modal-backdrop">
          <section aria-labelledby="action-title" aria-modal="true" className="modal modal--compact" role="dialog">
            <h2 id="action-title">{actionTitle(pendingAction.action)} {pendingAction.setup.displayName}?</h2>
            <p>{actionDescription(pendingAction.action)}</p>
            <div className="modal__actions">
              <button className="button button--secondary" disabled={actionBusy} onClick={() => setPendingAction(undefined)} type="button">Cancel</button>
              <button className={pendingAction.action === 'forget' ? 'button button--danger' : 'button'} disabled={actionBusy} onClick={() => void executeAction()} type="button">
                {actionBusy ? 'Working…' : actionTitle(pendingAction.action)}
              </button>
            </div>
          </section>
        </div>
      )}
    </section>
  );
}

function Field({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return <label className="field"><span>{label}</span>{children}</label>;
}

function StateBadge({ value }: { readonly value: string }) {
  const normalized = value.toLowerCase();
  const tone = normalized === 'connected' || normalized === 'up' || normalized === 'ready' || normalized === 'available'
    ? 'positive'
    : normalized === 'detached' || normalized === 'down' || normalized === 'unhealthy' || normalized === 'unavailable'
      ? 'negative'
      : 'warning';
  return <span className={`badge badge--${tone}`}>{humanize(value)}</span>;
}

function SetupDetailsView({ details, health, capabilities }: {
  readonly details: SetupDetails;
  readonly health: SetupHealth;
  readonly capabilities: SetupCapabilities;
}) {
  const rows = [
    ['Setup ID', details.setup.setupId],
    ['Host', `${details.setup.host}:${details.setup.port}`],
    ['Database', details.setup.database],
    ['Schema', details.setup.schema],
    ['Migration', details.migrationVersion],
    ['Pool size', String(details.runtime.poolMaxSize)],
    ['Registered', formatDisplayInstant(details.registeredAt)],
    ['Connected', details.connectedAt === null ? 'Not connected' : formatDisplayInstant(details.connectedAt)],
  ];
  return (
    <div className="setup-details">
      <dl className="details-list">
        {rows.map(([label, value]) => (
          <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
        ))}
      </dl>
      <section className="details-section" aria-labelledby="health-title">
        <h3 id="health-title">Database health</h3>
        <p><StateBadge value={health.status} /> {health.latencyMillis} ms · {health.schemaReady ? 'Schema ready' : 'Schema unavailable'}</p>
        <p>{health.detail}</p>
        <small>Checked {formatDisplayInstant(health.checkedAt)}</small>
      </section>
      <section className="details-section" aria-labelledby="capabilities-title">
        <h3 id="capabilities-title">Capabilities</h3>
        <ul className="capability-list">
          {Object.entries(capabilities.capabilities).map(([name, available]) => (
            <li key={name}>
              <span>{humanizeCapability(name)}</span>
              <StateBadge value={available ? 'AVAILABLE' : 'UNAVAILABLE'} />
            </li>
          ))}
        </ul>
        <dl className="details-list details-list--limits">
          <div><dt>Maximum value</dt><dd>{formatBytes(capabilities.limits.maximumValueBytes)}</dd></div>
          <div><dt>Pub/Sub payload</dt><dd>{formatBytes(capabilities.limits.pubSubPayloadMaxBytes)}</dd></div>
          <div><dt>Pub/Sub channel</dt><dd>{formatBytes(capabilities.limits.pubSubChannelMaxBytes)}</dd></div>
        </dl>
      </section>
    </div>
  );
}

function ProblemAlert({ problem }: { readonly problem: ManagementClientError }) {
  return (
    <div className="diagnostics" role="alert">
      <strong>{problem.code}</strong>
      <p>{problem.message}</p>
      {problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}
    </div>
  );
}

function asClientError(failure: unknown): ManagementClientError {
  return failure instanceof ManagementClientError
    ? failure
    : new ManagementClientError(0, 'CONNECTION_FAILED', 'The setup request could not be completed');
}

function humanize(value: string): string {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./u, (letter) => letter.toUpperCase());
}

function humanizeCapability(value: string): string {
  return value
    .replace(/([a-z])([A-Z])/gu, '$1 $2')
    .toLowerCase()
    .replace(/^./u, (letter) => letter.toUpperCase());
}

function formatBytes(value: number): string {
  return `${value.toLocaleString('en-US')} bytes`;
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
