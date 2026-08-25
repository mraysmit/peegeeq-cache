import { useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';

import type { BrowserSession } from '../api/session-client';

type ManagementShellProps = {
  session: BrowserSession;
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

export function ManagementShell({ session, onLogout }: ManagementShellProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const isOperator = session.roles.includes('operator');

  return (
    <div className="console" data-theme={theme}>
      <header className="console__header">
        <div>
          <p className="console__eyebrow">PeeGeeQ Cache</p>
          <strong>Management Console</strong>
        </div>
        <div className="console__status" aria-label="Session and connection status">
          <span className="status status--connected"><i aria-hidden="true" />Connected</span>
          <span>{session.user}</span>
          <span className="role">{isOperator ? 'Operator' : 'Viewer'}</span>
          <button
            aria-label={theme === 'light' ? 'Use dark theme' : 'Use light theme'}
            className="icon-button"
            onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
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
            <button className="session-button" onClick={() => void onLogout()} type="button">
              End local session
            </button>
          )}
        </div>
      </header>
      <nav className="console__sidebar" aria-label="Management sections">
        {sections.map((section) => (
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
        <Routes>
          {sections.map((section) => (
            <Route
              element={<Section title={section.label} />}
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
          <p>No management notifications.</p>
        </aside>
      )}
    </div>
  );
}

function Section({ title }: { title: string }) {
  return (
    <section className="workspace" aria-labelledby="workspace-title">
      <p className="workspace__context">Authenticated workspace</p>
      <h1 id="workspace-title">{title}</h1>
      <p>This management area is ready for its feature phase.</p>
    </section>
  );
}
