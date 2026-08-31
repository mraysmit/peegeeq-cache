import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { NavLink, Navigate, Route, Routes, useParams } from 'react-router-dom';

import type { BrowserSession, ManagementClientError, SessionClient } from '../api/session-client';
import { SetupClient } from '../api/setup-client';
import { InspectionClient } from '../api/inspection-client';
import { EntryAdministrationClient } from '../api/entry-administration-client';
import { ResourceClient } from '../api/resource-client';
import { PubSubClient } from '../api/pubsub-client';
import type { SetupCapabilities } from '../api/setup-schemas';
import { OverviewPage } from '../features/overview/OverviewPage';
import { NamespaceDetailsPage } from '../features/namespaces/NamespaceDetailsPage';
import { NamespacesPage } from '../features/namespaces/NamespacesPage';
import { SetupsPage } from '../features/setups/SetupsPage';
import { EntriesPage } from '../features/entries/EntriesPage';
import { EntryDetailsPage } from '../features/entries/EntryDetailsPage';
import { CountersPage } from '../features/counters/CountersPage';
import { LocksPage } from '../features/locks/LocksPage';
import { PubSubPage } from '../features/pubsub/PubSubPage';
import { MonitoringPage } from '../features/monitoring/MonitoringPage';
import { SettingsPage } from '../features/settings/SettingsPage';
import { useSetupScopeStore } from '../state/scope-store';
import { loadPreferences, PREFERENCES_CHANGED_EVENT, savePreferences } from '../state/preferences';
import { BrowserMonitoringSocket, type MonitoringConnectionState, type MonitoringEnvelope } from '../api/monitoring-live';
import { formatDisplayInstant } from '../presentation/display-time';

type ManagementShellProps = {
  session: BrowserSession;
  sessionClient: SessionClient;
  sessionProblem?: ManagementClientError;
  onLogout: () => Promise<void>;
};

const sections = [
  { label: 'Overview', path: '/' },
  { label: 'Setups', path: '/setups' },
  { label: 'Namespaces', path: '/namespaces' },
  { label: 'Keys', path: '/keys' },
  { label: 'Counters', path: '/counters' },
  { label: 'Locks', path: '/locks' },
  { label: 'Pub/Sub', path: '/pubsub' },
  { label: 'Monitoring', path: '/monitoring' },
  { label: 'Settings', path: '/settings' },
] as const;

export function ManagementShell({ session, sessionClient, sessionProblem, onLogout }: ManagementShellProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => loadPreferences().theme);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [endingSession, setEndingSession] = useState(false);
  const [connectionState, setConnectionState] = useState<MonitoringConnectionState>('STOPPED');
  const [notifications, setNotifications] = useState<MonitoringEnvelope[]>([]);
  const selectedSetupId = useSetupScopeStore((state) => state.setupId);
  const selectedNamespace = useSetupScopeStore((state) => state.namespace);
  const selectedCapabilities = useSetupScopeStore((state) => state.capabilities);
  const selectStoredSetup = useSetupScopeStore((state) => state.select);
  const selectNamespace = useSetupScopeStore((state) => state.selectNamespace);
  const clearStoredSetup = useSetupScopeStore((state) => state.clear);
  const setupClient = useMemo(() => new SetupClient(sessionClient), [sessionClient]);
  const inspectionClient = useMemo(() => new InspectionClient(sessionClient), [sessionClient]);
  const entryAdministrationClient = useMemo(() => new EntryAdministrationClient(sessionClient), [sessionClient]);
  const resourceClient = useMemo(() => new ResourceClient(sessionClient), [sessionClient]);
  const pubSubClient = useMemo(() => new PubSubClient(sessionClient), [sessionClient]);
  const monitoringSocket = useMemo(() => new BrowserMonitoringSocket(), []);
  const isOperator = session.roles.includes('operator');

  useEffect(() => {
    const applyPreferences = () => setTheme(loadPreferences().theme);
    window.addEventListener(PREFERENCES_CHANGED_EVENT, applyPreferences);
    return () => window.removeEventListener(PREFERENCES_CHANGED_EVENT, applyPreferences);
  }, []);

  const selectSetup = (setupId: string | undefined, capabilities?: SetupCapabilities) => {
    if (setupId === undefined) {
      clearStoredSetup();
      return;
    }
    if (capabilities !== undefined) selectStoredSetup(setupId, capabilities);
  };

  useEffect(() => {
    if (selectedSetupId === undefined || selectedCapabilities !== undefined) return undefined;
    let active = true;
    void setupClient.capabilities(selectedSetupId)
      .then((capabilities) => {
        if (active) selectStoredSetup(selectedSetupId, capabilities);
      })
      .catch(() => {
        if (active) clearStoredSetup();
      });
    return () => {
      active = false;
    };
  }, [clearStoredSetup, selectStoredSetup, selectedCapabilities, selectedSetupId, setupClient]);

  useEffect(() => {
    if (selectedSetupId === undefined || !notificationsOpen) return undefined;
    const live = monitoringSocket.connect(selectedSetupId, (event) => setNotifications((current) => [event, ...current.filter((item) => item.eventId !== event.eventId)].slice(0, 100)), setConnectionState);
    return () => { live.stop(); setNotifications([]); };
  }, [monitoringSocket, notificationsOpen, selectedSetupId]);

  const capabilityForPath = (path: string): keyof SetupCapabilities['capabilities'] | undefined => {
    if (path === '/namespaces' || path === '/keys') return 'namespaceInspection';
    if (path === '/counters') return 'counterInspection';
    if (path === '/locks') return 'lockInspection';
    if (path === '/pubsub') return 'pubSub';
    return undefined;
  };
  const visibleSections = sections.filter((section) => {
    const capability = capabilityForPath(section.path);
    if (capability === undefined || selectedSetupId === undefined) return true;
    return selectedCapabilities?.capabilities[capability] === true;
  });
  const gated = (
    capability: keyof SetupCapabilities['capabilities'],
    label: string,
    description: string,
    content: ReactNode,
  ) => {
    if (selectedSetupId === undefined) return content;
    if (selectedCapabilities === undefined) return <CapabilityPending label={label} />;
    return selectedCapabilities.capabilities[capability]
      ? content
      : <CapabilityUnavailable description={description} label={label} />;
  };

  const endSession = async () => {
    setEndingSession(true);
    try {
      await onLogout();
    } finally {
      setEndingSession(false);
    }
  };
  const toggleTheme = () => {
    const next = theme === 'light' ? 'dark' : 'light';
    setTheme(next); savePreferences({ ...loadPreferences(), theme: next });
  };

  return (
    <div className="console" data-theme={theme}>
      <header className="console__header">
        <div>
          <p className="console__eyebrow">PeeGeeQ Cache</p>
          <strong>Management Console</strong>
        </div>
        <div className="console__status" aria-label="Session and connection status">
          <span className={`status ${connectionState === 'STALE' ? 'status--warning' : 'status--connected'}`}><i aria-hidden="true" />{selectedSetupId === undefined || !notificationsOpen ? 'Connected' : connectionState === 'CONNECTED' ? 'Live' : connectionState === 'STALE' ? 'Live stale' : 'Connecting'}</span>
          <span>{session.user}</span>
          <span className="role">{isOperator ? 'Operator' : 'Viewer'}</span>
          <span className="scope" title="Active setup scope">
            {selectedSetupId === undefined ? 'No setup selected' : `Setup: ${selectedSetupId}`}
          </span>
          <button
            aria-label={theme === 'light' ? 'Use dark theme' : 'Use light theme'}
            className="icon-button"
            onClick={toggleTheme}
            type="button"
          >
            {theme === 'light' ? 'Dark' : 'Light'}
          </button>
          <button
            aria-expanded={notificationsOpen}
            aria-label={notificationsOpen ? 'Close notifications' : 'Open notifications'}
            className="icon-button"
            onClick={() => setNotificationsOpen(!notificationsOpen)}
            type="button"
          >
            Notices
          </button>
          {session.authenticationMode === 'LOCAL_TOKEN' && (
            <button
              className="session-button"
              disabled={endingSession}
              onClick={() => void endSession()}
              type="button"
            >
              {endingSession ? 'Ending session…' : 'End local session'}
            </button>
          )}
        </div>
      </header>
      <nav className="console__sidebar" aria-label="Management sections">
        {visibleSections.map((section) => (
          <NavLink
            className={({ isActive }) => isActive ? 'nav-link nav-link--active' : 'nav-link'}
            end={section.path === '/'}
            key={section.path}
            to={section.path}
          >
            {section.label}
          </NavLink>
        ))}
      </nav>
      <main className="console__main" id="main-content">
        {sessionProblem !== undefined && (
          <div className="diagnostics" role="alert">
            <strong>{sessionProblem.code}</strong>
            <p>{sessionProblem.message}</p>
            {sessionProblem.correlationId !== undefined
              && <p>Correlation: {sessionProblem.correlationId}</p>}
          </div>
        )}
        <Routes>
          <Route
            element={<OverviewPage client={inspectionClient} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />}
            path="/"
          />
          <Route
            element={gated('namespaceInspection', 'Namespaces', 'namespace inspection', <NamespacesPage client={inspectionClient} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)}
            path="/namespaces"
          />
          <Route
            element={gated('namespaceInspection', 'Namespaces', 'namespace inspection', (
              <NamespaceDetailsRoute
                client={inspectionClient}
                onSelectNamespace={selectNamespace}
                selectedSetupId={selectedSetupId}
              />
            ))}
            path="/namespaces/:encodedNamespace"
          />
          <Route
            element={(
              <SetupsPage
                client={setupClient}
                onSelectSetup={selectSetup}
                selectedSetupId={selectedSetupId}
                session={session}
              />
            )}
            path="/setups"
          />
          <Route
            element={gated('namespaceInspection', 'Keys', 'namespace and entry inspection', <EntriesPage administrationClient={entryAdministrationClient} canBulkDelete={isOperator && selectedCapabilities?.capabilities.bulkEntryDelete === true} canInspectExpired={selectedCapabilities?.capabilities.expiredEntryInspection === true} canOperate={isOperator} client={inspectionClient} key={`${selectedSetupId ?? 'no-setup'}:${selectedNamespace ?? 'no-namespace'}`} selectedNamespace={selectedNamespace} selectedSetupId={selectedSetupId} />)}
            path="/keys"
          />
          <Route
            element={gated('namespaceInspection', 'Keys', 'namespace and entry inspection', <EntryDetailsRoute administrationClient={entryAdministrationClient} canOperate={isOperator} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.entryValueReveal === true} client={inspectionClient} selectedSetupId={selectedSetupId} />)}
            path="/keys/:encodedNamespace/:encodedKey"
          />
          <Route element={gated('counterInspection', 'Counters', 'counter inspection', <CountersPage canBulkDelete={isOperator && selectedCapabilities?.capabilities.bulkCounterDelete === true} canOperate={isOperator} client={resourceClient} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)} path="/counters" />
          <Route element={gated('lockInspection', 'Locks', 'lock inspection', <LocksPage canOperate={isOperator && selectedCapabilities?.capabilities.forcedLockRelease === true} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.lockOwnerReveal === true} client={resourceClient} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)} path="/locks" />
          <Route element={gated('pubSub', 'Pub/Sub', 'Pub/Sub', <PubSubPage canOperate={isOperator} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.pubSubPayloadReveal === true} client={pubSubClient} key={selectedSetupId ?? 'no-setup'} maximumChannelBytes={selectedCapabilities?.limits.pubSubChannelMaxBytes ?? 63} maximumPayloadBytes={selectedCapabilities?.limits.pubSubPayloadMaxBytes ?? 7_500} selectedSetupId={selectedSetupId} />)} path="/pubsub" />
          <Route element={<MonitoringPage client={inspectionClient} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/monitoring" />
          <Route element={<SettingsPage capabilities={selectedCapabilities} selectedSetupId={selectedSetupId} session={session} />} path="/settings" />
          {sections.filter((section) => !['/setups', '/', '/namespaces', '/keys', '/counters', '/locks', '/pubsub', '/monitoring', '/settings'].includes(section.path)).map((section) => (
            <Route
              element={<Section selectedSetupId={selectedSetupId} title={section.label} />}
              key={section.path}
              path={section.path}
            />
          ))}
          <Route element={<Navigate replace to="/" />} path="*" />
        </Routes>
      </main>
      {notificationsOpen && (
        <aside aria-label="Notifications" className="notifications">
          <div>
            <h2>Notifications</h2>
            <button
              aria-label="Close notifications"
              className="icon-button"
              onClick={() => setNotificationsOpen(false)}
              type="button"
            >
              Close
            </button>
          </div>
          {notifications.length === 0 ? <p>No management notifications.</p> : <ol>{notifications.map((event) => <li key={event.eventId}><strong>{event.type.replaceAll('.', ' ')}</strong><br /><time dateTime={event.occurredAt}>{formatDisplayInstant(event.occurredAt)}</time></li>)}</ol>}
        </aside>
      )}
    </div>
  );
}

function CapabilityPending({ label }: { readonly label: string }) {
  return <section aria-labelledby="capability-pending-title" className="workspace"><h1 id="capability-pending-title">Loading {label}</h1><p aria-busy="true">Loading setup capabilities…</p></section>;
}

function CapabilityUnavailable({ description, label }: { readonly description: string; readonly label: string }) {
  return <section aria-labelledby="capability-unavailable-title" className="workspace"><h1 id="capability-unavailable-title">{label} unavailable</h1><p>The active setup does not provide {description}.</p></section>;
}

function EntryDetailsRoute({ administrationClient, canOperate, canReveal, client, selectedSetupId }: {
  readonly administrationClient: EntryAdministrationClient;
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly client: InspectionClient;
  readonly selectedSetupId?: string;
}) {
  const { encodedNamespace, encodedKey } = useParams();
  if (encodedNamespace === undefined || encodedKey === undefined) return <Navigate replace to="/keys" />;
  return <EntryDetailsPage administrationClient={administrationClient} canOperate={canOperate} canReveal={canReveal} client={client} encodedKey={encodedKey} encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}:${encodedKey}`} selectedSetupId={selectedSetupId} />;
}

function NamespaceDetailsRoute({ client, selectedSetupId, onSelectNamespace }: {
  readonly client: InspectionClient;
  readonly selectedSetupId?: string;
  readonly onSelectNamespace: (namespace?: string) => void;
}) {
  const { encodedNamespace } = useParams();
  if (encodedNamespace === undefined) return <Navigate replace to="/namespaces" />;
  return <NamespaceDetailsPage client={client} encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}`} onSelectNamespace={onSelectNamespace} selectedSetupId={selectedSetupId} />;
}

function Section({ title, selectedSetupId }: { title: string; selectedSetupId?: string }) {
  return (
    <section className="workspace" aria-labelledby="workspace-title">
      <p className="workspace__context">Authenticated workspace</p>
      <h1 id="workspace-title">{title}</h1>
      <p>
        {selectedSetupId === undefined
          ? 'Select a connected setup before using this management area.'
          : `Active setup: ${selectedSetupId}. This management area is ready for its feature phase.`}
      </p>
    </section>
  );
}
