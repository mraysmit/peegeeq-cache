import { Alert, Button, Card, Form, Input, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { BrowserRouter } from 'react-router-dom';

import { ManagementClientError, SessionClient, type BrowserSession } from '../api/session-client';
import { createManagementClients, createManagementStore, managementApi, ManagementProvider } from '../store';
import { ErrorBoundary } from './ErrorBoundary';
import { ManagementShell } from './ManagementShell';

type AppProps = { apiBaseUrl?: string };

export function App({ apiBaseUrl = '' }: AppProps) {
  const client = useMemo(() => new SessionClient(apiBaseUrl), [apiBaseUrl]);
  const store = useMemo(() => createManagementStore(createManagementClients(client)), [client]);
  const [session, setSession] = useState<BrowserSession>();
  const [loading, setLoading] = useState(true);
  const [loginRequired, setLoginRequired] = useState(false);
  const [problem, setProblem] = useState<ManagementClientError>();

  const bootstrap = () => {
    setLoading(true);
    setProblem(undefined);
    void client.load()
      .then((loaded) => {
        setSession(loaded);
        setLoginRequired(false);
      })
      .catch((failure: unknown) => {
        client.clear();
        store.dispatch(managementApi.util.resetApiState());
        setSession(undefined);
        if (failure instanceof ManagementClientError && failure.status === 401) {
          setLoginRequired(true);
          return;
        }
        setProblem(asClientError(failure));
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    let active = true;
    void client.load()
      .then((loaded) => {
        if (!active) return;
        setSession(loaded);
        setLoginRequired(false);
      })
      .catch((failure: unknown) => {
        if (!active) return;
        client.clear();
        store.dispatch(managementApi.util.resetApiState());
        setSession(undefined);
        if (failure instanceof ManagementClientError && failure.status === 401) {
          setLoginRequired(true);
          return;
        }
        setProblem(asClientError(failure));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [client, store]);

  useEffect(() => {
    if (session === undefined) return undefined;
    const expiresAt = Math.min(
      Date.parse(session.sessionIdleExpiresAt),
      Date.parse(session.sessionExpiresAt),
    );
    let timer = 0;
    const scheduleExpiryCheck = (remaining: number) => {
      timer = window.setTimeout(() => {
        const nextRemaining = expiresAt - Date.now();
        if (nextRemaining > 0) {
          scheduleExpiryCheck(nextRemaining);
          return;
        }
        client.clear();
        store.dispatch(managementApi.util.resetApiState());
        setSession(undefined);
        setLoginRequired(true);
      }, Math.max(1, Math.min(remaining, 2_147_483_647)));
    };
    scheduleExpiryCheck(expiresAt - Date.now());
    return () => window.clearTimeout(timer);
  }, [client, session, store]);

  const login = async (token: string) => {
    setProblem(undefined);
    const loaded = await client.exchangeLocalToken(token);
    setSession(loaded);
    setLoginRequired(false);
  };

  const logout = async () => {
    setProblem(undefined);
    try {
      await client.logoutLocal();
      client.clear();
      store.dispatch(managementApi.util.resetApiState());
      setSession(undefined);
      setLoginRequired(true);
    } catch (failure: unknown) {
      const clientFailure = asClientError(failure);
      if (clientFailure.status === 401) {
        client.clear();
        store.dispatch(managementApi.util.resetApiState());
        setSession(undefined);
        setLoginRequired(true);
        return;
      }
      setProblem(clientFailure);
    }
  };

  if (loading) return <SessionGate title="Connecting to management server" />;
  if (session !== undefined) {
    return (
      <ErrorBoundary>
        <BrowserRouter basename="/ui">
          <ManagementProvider store={store}>
            <ManagementShell
              session={session}
              sessionProblem={problem}
              onLogout={logout}
            />
          </ManagementProvider>
        </BrowserRouter>
      </ErrorBoundary>
    );
  }
  if (loginRequired) return <LocalTokenLogin onLogin={login} />;
  return <ProblemView problem={problem} onRetry={bootstrap} />;
}

function LocalTokenLogin({ onLogin }: { onLogin: (token: string) => Promise<void> }) {
  const [token, setToken] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState<ManagementClientError>();

  const submit = async () => {
    setSubmitting(true);
    setProblem(undefined);
    try {
      await onLogin(token);
    } catch (failure: unknown) {
      setProblem(asClientError(failure));
    } finally {
      setToken('');
      setSubmitting(false);
    }
  };

  return (
    <SessionCard title="Connect to management console">
      <Typography.Paragraph>Enter the one-time bootstrap token printed by the local management server.</Typography.Paragraph>
      <Form layout="vertical" onFinish={() => void submit()}>
        <Form.Item htmlFor="bootstrap-token" label="Bootstrap token">
          <Input.Password
            autoComplete="off"
            id="bootstrap-token"
            onChange={(event) => setToken(event.target.value)}
            required
            spellCheck={false}
            value={token}
          />
        </Form.Item>
        <Button disabled={submitting || token.length === 0} htmlType="submit" type="primary">
          {submitting ? 'Connecting…' : 'Connect'}
        </Button>
      </Form>
      {problem !== undefined && <Diagnostics problem={problem} />}
    </SessionCard>
  );
}

function SessionGate({ title }: { title: string }) {
  return (
    <SessionCard busy title={title}>
      <Spin />
      <Typography.Paragraph>Establishing an authenticated browser session.</Typography.Paragraph>
    </SessionCard>
  );
}

function ProblemView({ problem, onRetry }: {
  problem: ManagementClientError | undefined;
  onRetry: () => void;
}) {
  return (
    <SessionCard title="Management server unavailable">
      {problem !== undefined && <Diagnostics problem={problem} />}
      <Button onClick={onRetry} type="primary">Retry connection</Button>
    </SessionCard>
  );
}

/** Centred pre-session surface (login, connecting, unavailable) built from antd `Card`. */
function SessionCard({ busy, children, title }: { busy?: boolean; children: ReactNode; title: string }) {
  return (
    <main aria-busy={busy === true ? 'true' : undefined} className="session-gate">
      <Card className="session-card">
        <Typography.Text className="console__eyebrow" type="secondary">PeeGeeQ Cache</Typography.Text>
        <Typography.Title level={1}>{title}</Typography.Title>
        {children}
      </Card>
    </main>
  );
}

function Diagnostics({ problem }: { problem: ManagementClientError }) {
  return (
    <Alert
      description={(
        <>
          <p>{problem.message}</p>
          {problem.correlationId !== undefined && <p>Correlation: {problem.correlationId}</p>}
        </>
      )}
      message={problem.code}
      showIcon
      type="error"
    />
  );
}

function asClientError(failure: unknown): ManagementClientError {
  return failure instanceof ManagementClientError
    ? failure
    : new ManagementClientError(0, 'CONNECTION_FAILED', 'The management server could not be reached');
}
