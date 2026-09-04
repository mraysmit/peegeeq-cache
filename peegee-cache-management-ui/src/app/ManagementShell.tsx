import { BellOutlined, ClearOutlined, CloseOutlined, DatabaseOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { Alert, Badge, Button, ConfigProvider, Drawer, Empty, Layout, List, Menu, Space, Tag, Typography, theme as antdTheme } from 'antd';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';

import type { BrowserSession, ManagementClientError, SessionClient } from '../api/session-client';
import type { SetupCapabilities } from '../api/setup-schemas';
import { BrowserMonitoringSocket } from '../api/monitoring-live';
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
import { AdvancedOperationsPage } from '../features/advanced/AdvancedOperationsPage';
import { useSetupScopeStore } from '../state/scope-store';
import { useLiveStore } from '../state/live-store';
import { loadPreferences, PREFERENCES_CHANGED_EVENT, savePreferences } from '../state/preferences';
import { formatDisplayInstant } from '../presentation/display-time';
import { useManagementClients } from '../store';
import { useGetSetupCapabilitiesQuery } from '../store/api/setupsApi';
import { ConnectionStatus } from '../components/common/ConnectionStatus';

const { Header, Sider, Content } = Layout;
const { Text, Title } = Typography;

type ManagementShellProps = {
  session: BrowserSession;
  /** Retained until every page reads through RTK Query (U11.3-U11.7); the shell itself no longer uses it. */
  sessionClient?: SessionClient;
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
  { label: 'Advanced', path: '/advanced' },
  { label: 'Settings', path: '/settings' },
] as const;

type SectionPath = (typeof sections)[number]['path'];

const capabilityForPath = (path: SectionPath): keyof SetupCapabilities['capabilities'] | undefined => {
  switch (path) {
    case '/namespaces': return 'namespaceInspection';
    case '/keys': return 'entryInspection';
    case '/counters': return 'counterInspection';
    case '/locks': return 'lockInspection';
    case '/pubsub': return 'pubSub';
    case '/advanced': return 'entryInspection';
    default: return undefined;
  }
};

function activeSection(pathname: string): SectionPath {
  if (pathname === '/') return '/';
  const match = sections.find((section) => section.path !== '/' && (pathname === section.path || pathname.startsWith(`${section.path}/`)));
  return match?.path ?? '/';
}

export function ManagementShell({ session, sessionProblem, onLogout }: ManagementShellProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => loadPreferences().theme);
  const [collapsed, setCollapsed] = useState(false);
  const [endingSession, setEndingSession] = useState(false);
  const location = useLocation();
  const clients = useManagementClients();
  const selectedSetupId = useSetupScopeStore((state) => state.setupId);
  const selectedNamespace = useSetupScopeStore((state) => state.namespace);
  const selectedCapabilities = useSetupScopeStore((state) => state.capabilities);
  const selectStoredSetup = useSetupScopeStore((state) => state.select);
  const selectNamespace = useSetupScopeStore((state) => state.selectNamespace);
  const clearStoredSetup = useSetupScopeStore((state) => state.clear);
  const connectionState = useLiveStore((state) => state.connectionState);
  const notificationsOpen = useLiveStore((state) => state.notificationsOpen);
  const notifications = useLiveStore((state) => state.notifications);
  const toggleNotifications = useLiveStore((state) => state.toggleNotifications);
  const closeNotifications = useLiveStore((state) => state.closeNotifications);
  const setConnectionState = useLiveStore((state) => state.setConnectionState);
  const receiveNotification = useLiveStore((state) => state.receive);
  const clearNotifications = useLiveStore((state) => state.clearNotifications);
  const resetLive = useLiveStore((state) => state.reset);
  const monitoringSocket = useMemo(() => new BrowserMonitoringSocket(), []);
  const isOperator = session.roles.includes('operator');

  useEffect(() => {
    const applyPreferences = () => setTheme(loadPreferences().theme);
    window.addEventListener(PREFERENCES_CHANGED_EVENT, applyPreferences);
    return () => window.removeEventListener(PREFERENCES_CHANGED_EVENT, applyPreferences);
  }, []);

  // Capabilities for a restored scope come through RTK Query; the Zustand scope store remains
  // the owner of the selected setup and its capability snapshot (design §8.2).
  const capabilityLookup = useGetSetupCapabilitiesQuery(
    { setupId: selectedSetupId ?? '' },
    { skip: selectedSetupId === undefined || selectedCapabilities !== undefined },
  );
  useEffect(() => {
    if (selectedSetupId === undefined || selectedCapabilities !== undefined) return;
    if (capabilityLookup.data !== undefined) selectStoredSetup(selectedSetupId, capabilityLookup.data);
    else if (capabilityLookup.isError) clearStoredSetup();
  }, [capabilityLookup.data, capabilityLookup.isError, clearStoredSetup, selectStoredSetup, selectedCapabilities, selectedSetupId]);

  useEffect(() => {
    if (selectedSetupId === undefined || !notificationsOpen) return undefined;
    const live = monitoringSocket.connect(selectedSetupId, receiveNotification, setConnectionState);
    return () => { live.stop(); resetLive(); };
  }, [resetLive, monitoringSocket, notificationsOpen, receiveNotification, selectedSetupId, setConnectionState]);

  const selectSetup = (setupId: string | undefined, capabilities?: SetupCapabilities) => {
    if (setupId === undefined) {
      clearStoredSetup();
      return;
    }
    if (capabilities !== undefined) selectStoredSetup(setupId, capabilities);
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
    setTheme(next);
    savePreferences({ ...loadPreferences(), theme: next });
  };

  const liveLabel = selectedSetupId === undefined || !notificationsOpen
    ? 'Connected'
    : connectionState === 'CONNECTED' ? 'Live' : connectionState === 'STALE' ? 'Live stale' : 'Connecting';

  return (
    <ConfigProvider theme={{ algorithm: theme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm }}>
      <div className="console" data-theme={theme}>
        <Layout style={{ minHeight: '100vh' }}>
          <Sider aria-label="Primary navigation" collapsed={collapsed} collapsible theme="dark" trigger={null} width={240}>
            <div className="console__brand">
              <DatabaseOutlined style={{ color: '#fff', marginRight: collapsed ? 0 : 8 }} />
              {!collapsed && <Text strong style={{ color: '#fff' }}>PeeGeeQ Cache</Text>}
            </div>
            <nav aria-label="Management sections">
              <Menu
                items={visibleSections.map((section) => ({
                  key: section.path,
                  label: <Link to={section.path}>{section.label}</Link>,
                }))}
                mode="inline"
                selectedKeys={[activeSection(location.pathname)]}
                theme="dark"
              />
            </nav>
            <Button
              aria-expanded={!collapsed}
              aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
              block
              onClick={() => setCollapsed(!collapsed)}
              style={{ color: 'rgba(255,255,255,0.65)' }}
              type="text"
            >
              {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </Button>
          </Sider>
          <Layout>
            <Header className="console__header">
              <Space align="center" size="middle">
                <Text className="console__eyebrow" type="secondary">PeeGeeQ Cache</Text>
                <Text strong>Management Console</Text>
              </Space>
              <Space align="center" aria-label="Session and connection status" role="group" size="middle" wrap>
                <ConnectionStatus label={liveLabel} state={connectionState === 'STALE' && notificationsOpen ? 'warning' : 'connected'} />
                <Text>{session.user}</Text>
                <Tag color={isOperator ? 'geekblue' : 'default'}>{isOperator ? 'Operator' : 'Viewer'}</Tag>
                <Text title="Active setup scope" type="secondary">
                  {selectedSetupId === undefined ? 'No setup selected' : `Setup: ${selectedSetupId}`}
                </Text>
                <Button
                  aria-label={theme === 'light' ? 'Use dark theme' : 'Use light theme'}
                  onClick={toggleTheme}
                  size="small"
                >
                  {theme === 'light' ? 'Dark' : 'Light'}
                </Button>
                <Badge count={notificationsOpen ? 0 : notifications.length} size="small">
                  <Button
                    aria-expanded={notificationsOpen}
                    aria-label={notificationsOpen ? 'Close notifications' : 'Open notifications'}
                    icon={<BellOutlined />}
                    onClick={toggleNotifications}
                    size="small"
                  >
                    Notices
                  </Button>
                </Badge>
                {session.authenticationMode === 'LOCAL_TOKEN' && (
                  <Button
                    danger
                    disabled={endingSession}
                    onClick={() => void endSession()}
                    size="small"
                  >
                    {endingSession ? 'Ending session…' : 'End local session'}
                  </Button>
                )}
              </Space>
            </Header>
            <Content className="console__main" id="main-content">
              {sessionProblem !== undefined && (
                <Alert
                  description={(
                    <>
                      <p>{sessionProblem.message}</p>
                      {sessionProblem.correlationId !== undefined && <p>Correlation: {sessionProblem.correlationId}</p>}
                    </>
                  )}
                  message={sessionProblem.code}
                  showIcon
                  style={{ marginBottom: 16 }}
                  type="error"
                />
              )}
              <Routes>
                <Route
                  element={<OverviewPage client={clients.inspection} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />}
                  path="/"
                />
                <Route
                  element={gated('namespaceInspection', 'Namespaces', 'namespace inspection', <NamespacesPage client={clients.inspection} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)}
                  path="/namespaces"
                />
                <Route
                  element={gated('namespaceInspection', 'Namespaces', 'namespace inspection', (
                    <NamespaceDetailsRoute
                      onSelectNamespace={selectNamespace}
                      selectedSetupId={selectedSetupId}
                    />
                  ))}
                  path="/namespaces/:encodedNamespace"
                />
                <Route
                  element={(
                    <SetupsPage
                      client={clients.setup}
                      onSelectSetup={selectSetup}
                      selectedSetupId={selectedSetupId}
                      session={session}
                    />
                  )}
                  path="/setups"
                />
                <Route
                  element={gated('entryInspection', 'Keys', 'entry inspection', <EntriesPage administrationClient={clients.entryAdministration} canBulkDelete={isOperator && selectedCapabilities?.capabilities.bulkEntryDelete === true} canInspectExpired={selectedCapabilities?.capabilities.expiredEntryInspection === true} canOperate={isOperator && selectedCapabilities?.capabilities.entryMutation === true} client={clients.inspection} key={`${selectedSetupId ?? 'no-setup'}:${selectedNamespace ?? 'no-namespace'}`} selectedNamespace={selectedNamespace} selectedSetupId={selectedSetupId} />)}
                  path="/keys"
                />
                <Route
                  element={gated('entryInspection', 'Keys', 'entry inspection', <EntryDetailsRoute canOperate={isOperator && selectedCapabilities?.capabilities.entryMutation === true} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.entryValueReveal === true} selectedSetupId={selectedSetupId} />)}
                  path="/keys/:encodedNamespace/:encodedKey"
                />
                <Route element={gated('counterInspection', 'Counters', 'counter inspection', <CountersPage canBulkDelete={isOperator && selectedCapabilities?.capabilities.bulkCounterDelete === true} canOperate={isOperator && selectedCapabilities?.capabilities.counterMutation === true} client={clients.resource} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)} path="/counters" />
                <Route element={gated('lockInspection', 'Locks', 'lock inspection', <LocksPage canOperate={isOperator && selectedCapabilities?.capabilities.forcedLockRelease === true} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.lockOwnerReveal === true} client={clients.resource} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)} path="/locks" />
                <Route element={gated('pubSub', 'Pub/Sub', 'Pub/Sub', <PubSubPage canOperate={isOperator} canReveal={isOperator && session.features.sensitiveReveal && selectedCapabilities?.capabilities.pubSubPayloadReveal === true} client={clients.pubSub} key={selectedSetupId ?? 'no-setup'} maximumChannelBytes={selectedCapabilities?.limits.pubSubChannelMaxBytes ?? 63} maximumPayloadBytes={selectedCapabilities?.limits.pubSubPayloadMaxBytes ?? 7_500} selectedSetupId={selectedSetupId} />)} path="/pubsub" />
                <Route element={<MonitoringPage client={clients.inspection} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/monitoring" />
                <Route element={gated('entryInspection', 'Advanced operations', 'backend operations', <AdvancedOperationsPage canBatch={selectedCapabilities?.capabilities.batchEntryOperations === true} canMetrics={selectedCapabilities?.capabilities.cacheMetrics === true} canOperate={isOperator} canOwnLocks={selectedCapabilities?.capabilities.ownerLockOperations === true} canReveal={isOperator && session.features.sensitiveReveal} canScan={selectedCapabilities?.capabilities.valueScan === true} client={clients.backendCapability} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />)} path="/advanced" />
                <Route element={<SettingsPage capabilities={selectedCapabilities} selectedSetupId={selectedSetupId} session={session} />} path="/settings" />
                <Route element={<Navigate replace to="/" />} path="*" />
              </Routes>
            </Content>
          </Layout>
        {/* Reference pattern (peegeeq-management-ui Header.tsx): notifications in an antd Drawer.
            The aside inside is the complementary landmark the browser suite addresses. */}
        <Drawer
          aria-label="Notifications"
          extra={(
            <Button
              disabled={notifications.length === 0}
              icon={<ClearOutlined />}
              onClick={clearNotifications}
              size="small"
            >
              Clear
            </Button>
          )}
          onClose={closeNotifications}
          open={notificationsOpen}
          placement="right"
          title="Notifications"
          width={360}
        >
          <aside aria-label="Notifications" className="notifications">
            <Button
              aria-expanded={notificationsOpen}
              aria-label="Close notifications"
              icon={<CloseOutlined />}
              onClick={closeNotifications}
              size="small"
            >
              Close
            </Button>
            {notifications.length === 0
              ? <Empty description="No management notifications." />
              : (
                <List
                  dataSource={[...notifications]}
                  renderItem={(event) => (
                    <List.Item key={event.eventId}>
                      <List.Item.Meta
                        description={<time dateTime={event.occurredAt}>{formatDisplayInstant(event.occurredAt)}</time>}
                        title={event.type.replaceAll('.', ' ')}
                      />
                    </List.Item>
                  )}
                  size="small"
                />
              )}
          </aside>
        </Drawer>
        </Layout>
      </div>
    </ConfigProvider>
  );
}

function CapabilityPending({ label }: { readonly label: string }) {
  return (
    <section aria-labelledby="capability-pending-title" className="workspace">
      <Title id="capability-pending-title" level={1}>Loading {label}</Title>
      <Text aria-busy="true">Loading setup capabilities…</Text>
    </section>
  );
}

function CapabilityUnavailable({ description, label }: { readonly description: string; readonly label: string }) {
  return (
    <section aria-labelledby="capability-unavailable-title" className="workspace">
      <Title id="capability-unavailable-title" level={1}>{label} unavailable</Title>
      <Text>The active setup does not provide {description}.</Text>
    </section>
  );
}

function EntryDetailsRoute({ canOperate, canReveal, selectedSetupId }: {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly selectedSetupId?: string;
}) {
  const clients = useManagementClients();
  const { encodedNamespace, encodedKey } = useParams();
  if (encodedNamespace === undefined || encodedKey === undefined) return <Navigate replace to="/keys" />;
  return <EntryDetailsPage administrationClient={clients.entryAdministration} canOperate={canOperate} canReveal={canReveal} client={clients.inspection} encodedKey={encodedKey} encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}:${encodedKey}`} selectedSetupId={selectedSetupId} />;
}

function NamespaceDetailsRoute({ selectedSetupId, onSelectNamespace }: {
  readonly selectedSetupId?: string;
  readonly onSelectNamespace: (namespace?: string) => void;
}) {
  const clients = useManagementClients();
  const { encodedNamespace } = useParams();
  if (encodedNamespace === undefined) return <Navigate replace to="/namespaces" />;
  return <NamespaceDetailsPage client={clients.inspection} encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}`} onSelectNamespace={onSelectNamespace} selectedSetupId={selectedSetupId} />;
}
