import { BellOutlined, ClearOutlined, CloseOutlined, DatabaseOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { Alert, Badge, Button, ConfigProvider, Drawer, Empty, Layout, List, Menu, Space, Tag, Typography, theme as antdTheme } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';

import type { BrowserSession, ManagementClientError } from '../api/session-client';
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
import { useGetSetupDetailsQuery } from '../store/api/setupsApi';
import { ConnectionStatus } from '../components/common/ConnectionStatus';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

/** Fallbacks used until the selected setup's details (and so its limits) have loaded. */
const DEFAULT_PUB_SUB_CHANNEL_MAX_BYTES = 63;
const DEFAULT_PUB_SUB_PAYLOAD_MAX_BYTES = 7_500;

type ManagementShellProps = {
  session: BrowserSession;
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
  const selectedSetupId = useSetupScopeStore((state) => state.setupId);
  const selectedNamespace = useSetupScopeStore((state) => state.namespace);
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

  // The selected setup's details carry the effective byte limits the Pub/Sub and Settings pages
  // show; the Zustand scope store remains the owner of the selection itself (design §8.2).
  const selectedDetails = useGetSetupDetailsQuery(
    { setupId: selectedSetupId ?? '' },
    { skip: selectedSetupId === undefined },
  );
  const selectedLimits = selectedDetails.data?.limits;

  useEffect(() => {
    if (selectedSetupId === undefined || !notificationsOpen) return undefined;
    const live = monitoringSocket.connect(selectedSetupId, receiveNotification, setConnectionState);
    return () => { live.stop(); resetLive(); };
  }, [resetLive, monitoringSocket, notificationsOpen, receiveNotification, selectedSetupId, setConnectionState]);

  const selectSetup = (setupId: string | undefined) => {
    if (setupId === undefined) clearStoredSetup();
    else selectStoredSetup(setupId);
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

  // `virtual={false}`: every console Select is a short, fixed option list. Without virtual
  // scrolling rc-select renders each visible option with role="option" instead of a hidden
  // three-item accessibility shadow list, so assistive technology and role-based locators see
  // the whole list — the accessibility contract the browser suite asserts.
  return (
    <ConfigProvider
      theme={{
        algorithm: theme === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: theme === 'dark'
          ? { colorError: '#ff7875', colorPrimary: '#85a5ff', colorTextSecondary: '#c8d2e1', colorTextTertiary: '#b5c1d2' }
          : { colorError: '#b42318', colorPrimary: '#0b57b7', colorTextSecondary: '#475569', colorTextTertiary: '#526176' },
      }}
      virtual={false}
    >
      <div className="console" data-theme={theme}>
        <Layout style={{ minHeight: '100vh' }}>
          <Sider aria-label="Primary navigation" collapsed={collapsed} collapsible theme="dark" trigger={null} width={240}>
            <div className="console__brand">
              <DatabaseOutlined style={{ color: '#fff', marginRight: collapsed ? 0 : 8 }} />
              {!collapsed && <Text strong style={{ color: '#fff' }}>PeeGeeQ Cache</Text>}
            </div>
            <nav aria-label="Management sections">
              <Menu
                items={sections.map((section) => ({
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
                    icon={<BellOutlined aria-hidden="true" />}
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
                  element={<OverviewPage key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />}
                  path="/"
                />
                <Route
                  element={<NamespacesPage key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />}
                  path="/namespaces"
                />
                <Route
                  element={(
                    <NamespaceDetailsRoute
                      onSelectNamespace={selectNamespace}
                      selectedSetupId={selectedSetupId}
                    />
                  )}
                  path="/namespaces/:encodedNamespace"
                />
                <Route
                  element={(
                    <SetupsPage
                      onSelectSetup={selectSetup}
                      selectedSetupId={selectedSetupId}
                      session={session}
                    />
                  )}
                  path="/setups"
                />
                <Route
                  element={<EntriesPage canBulkDelete={isOperator} canOperate={isOperator} key={`${selectedSetupId ?? 'no-setup'}:${selectedNamespace ?? 'no-namespace'}`} selectedNamespace={selectedNamespace} selectedSetupId={selectedSetupId} />}
                  path="/keys"
                />
                <Route
                  element={<EntryDetailsRoute canOperate={isOperator} canReveal={isOperator} selectedSetupId={selectedSetupId} />}
                  path="/keys/:encodedNamespace/:encodedKey"
                />
                <Route element={<CountersPage canBulkDelete={isOperator} canOperate={isOperator} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/counters" />
                <Route element={<LocksPage canOperate={isOperator} canReveal={isOperator} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/locks" />
                <Route element={<PubSubPage canOperate={isOperator} canReveal={isOperator} key={selectedSetupId ?? 'no-setup'} maximumChannelBytes={selectedLimits?.pubSubChannelMaxBytes ?? DEFAULT_PUB_SUB_CHANNEL_MAX_BYTES} maximumPayloadBytes={selectedLimits?.pubSubPayloadMaxBytes ?? DEFAULT_PUB_SUB_PAYLOAD_MAX_BYTES} selectedSetupId={selectedSetupId} />} path="/pubsub" />
                <Route element={<MonitoringPage key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/monitoring" />
                <Route element={<AdvancedOperationsPage canOperate={isOperator} canReveal={isOperator} key={selectedSetupId ?? 'no-setup'} selectedSetupId={selectedSetupId} />} path="/advanced" />
                <Route element={<SettingsPage limits={selectedLimits} migrationVersion={selectedDetails.data?.migrationVersion} selectedSetupId={selectedSetupId} session={session} />} path="/settings" />
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
              icon={<ClearOutlined aria-hidden="true" />}
              onClick={clearNotifications}
              size="small"
            >
              Clear
            </Button>
          )}
          mask={false}
          onClose={closeNotifications}
          open={notificationsOpen}
          placement="right"
          rootClassName="notifications-drawer"
          title="Notifications"
          width={360}
        >
          <aside aria-label="Notifications" className="notifications">
            <Button
              aria-expanded={notificationsOpen}
              aria-label="Close notifications"
              icon={<CloseOutlined aria-hidden="true" />}
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

function EntryDetailsRoute({ canOperate, canReveal, selectedSetupId }: {
  readonly canOperate: boolean;
  readonly canReveal: boolean;
  readonly selectedSetupId?: string;
}) {
  const { encodedNamespace, encodedKey } = useParams();
  if (encodedNamespace === undefined || encodedKey === undefined) return <Navigate replace to="/keys" />;
  return <EntryDetailsPage canOperate={canOperate} canReveal={canReveal} encodedKey={encodedKey} encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}:${encodedKey}`} selectedSetupId={selectedSetupId} />;
}

function NamespaceDetailsRoute({ selectedSetupId, onSelectNamespace }: {
  readonly selectedSetupId?: string;
  readonly onSelectNamespace: (namespace?: string) => void;
}) {
  const { encodedNamespace } = useParams();
  if (encodedNamespace === undefined) return <Navigate replace to="/namespaces" />;
  return <NamespaceDetailsPage encodedNamespace={encodedNamespace} key={`${selectedSetupId ?? 'no-setup'}:${encodedNamespace}`} onSelectNamespace={onSelectNamespace} selectedSetupId={selectedSetupId} />;
}
