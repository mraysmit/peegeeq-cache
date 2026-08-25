import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { BrowserRouter } from 'react-router-dom';

import { ManagementClientError, SessionClient, type BrowserSession } from '../api/session-client';
import { ErrorBoundary } from './ErrorBoundary';
import { ManagementShell } from './ManagementShell';

type AppProps = { apiBaseUrl?: string };

export function App({ apiBaseUrl = '' }: AppProps) {
  const client = useMemo(() => new SessionClient(apiBaseUrl), [apiBaseUrl]);
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
  }, [client]);

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
        setSession(undefined);
        setLoginRequired(true);
      }, Math.max(1, Math.min(remaining, 2_147_483_647)));
    };
    scheduleExpiryCheck(expiresAt - Date.now());
    return () => window.clearTimeout(timer);
  }, [client, session]);

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
      setSession(undefined);
      setLoginRequired(true);
    } catch (failure: unknown) {
      const clientFailure = asClientError(failure);
      if (clientFailure.status === 401) {
        client.clear();
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
          <ManagementShell
            session={session}
            sessionClient={client}
            sessionProblem={problem}
            onLogout={logout}
          />
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

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
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
    <main className="session-gate">
      <section className="session-card">
        <p className="console__eyebrow">PeeGeeQ Cache</p>
        <h1>Connect to management console</h1>
        <p>Enter the one-time bootstrap token printed by the local management server.</p>
        <form onSubmit={(event) => void submit(event)}>
          <label htmlFor="bootstrap-token">Bootstrap token</label>
          <input
            autoComplete="off"
            id="bootstrap-token"
            onChange={(event) => setToken(event.target.value)}
            required
            spellCheck={false}
            type="password"
            value={token}
          />
          <button disabled={submitting || token.length === 0} type="submit">
            {submitting ? 'Connecting…' : 'Connect'}
          </button>
        </form>
        {problem !== undefined && <Diagnostics problem={problem} />}
      </section>
    </main>
  );
}

function SessionGate({ title }: { title: string }) {
  return (
    <main className="session-gate" aria-busy="true">
      <section className="session-card">
        <p className="console__eyebrow">PeeGeeQ Cache</p>
        <h1>{title}</h1>
        <p>Establishing an authenticated browser session.</p>
      </section>
    </main>
  );
}

function ProblemView({ problem, onRetry }: {
  problem: ManagementClientError | undefined;
  onRetry: () => void;
}) {
  return (
    <main className="session-gate">
      <section className="session-card">
        <p className="console__eyebrow">PeeGeeQ Cache</p>
        <h1>Management server unavailable</h1>
        {problem !== undefined && <Diagnostics problem={problem} />}
        <button onClick={onRetry} type="button">Retry connection</button>
      </section>
    </main>
  );
}

function Diagnostics({ problem }: { problem: ManagementClientError }) {
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
    : new ManagementClientError(0, 'CONNECTION_FAILED', 'The management server could not be reached');
}
