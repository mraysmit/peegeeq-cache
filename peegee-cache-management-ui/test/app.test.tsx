import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { ManagementShell } from '@src/app/ManagementShell';
import { SessionClient, type BrowserSession } from '@src/api/session-client';
import { createManagementClients, createManagementStore, ManagementProvider } from '@src/store';
import type { SetupCapabilities } from '@src/api/setup-schemas';
import { useSetupScopeStore } from '@src/state/scope-store';

const session: BrowserSession = {
  user: 'alex',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: {
    setupRegistration: true,
    sensitiveReveal: true,
  },
};
const sessionClient = new SessionClient();
const store = createManagementStore(createManagementClients(sessionClient));

describe('U1 authenticated management shell', () => {
  it('renders route navigation, identity, connection state, and role-aware controls', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <ManagementProvider store={store}><ManagementShell session={session} onLogout={() => Promise.resolve()} /></ManagementProvider>
      </MemoryRouter>,
    );

    expect(screen.getByRole('navigation', { name: 'Management sections' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeVisible();
    expect(screen.getByText('alex')).toBeVisible();
    expect(screen.getByText('Operator')).toBeVisible();
    expect(screen.getByText('Connected')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Setups' })).toHaveAttribute('href', '/setups');
    expect(screen.getByRole('button', { name: 'End local session' })).toBeVisible();
  });

  it('provides theme and notification controls without exposing session secrets', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <MemoryRouter initialEntries={['/monitoring']}>
        <ManagementProvider store={store}><ManagementShell session={session} onLogout={() => Promise.resolve()} /></ManagementProvider>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Use dark theme' }));
    await user.click(screen.getByRole('button', { name: 'Open notifications' }));

    expect(container.firstElementChild).toHaveAttribute('data-theme', 'dark');
    expect(screen.getByRole('complementary', { name: 'Notifications' })).toBeVisible();
    expect(container).not.toHaveTextContent('csrf-token-with-at-least-thirty-two-characters');
  });

  it('blocks direct routes for capabilities the active setup does not provide', () => {
    const capabilities: SetupCapabilities = {
      migrationVersion: '1',
      capabilities: {
        namespaceInspection: true,
        entryInspection: true,
        expiredEntryInspection: true,
        entryMutation: true,
        counterInspection: false,
        counterMutation: false,
        lockInspection: false,
        forcedLockRelease: false,
        bulkEntryDelete: true,
        bulkCounterDelete: false,
        pubSub: false,
        databaseStatistics: true,
        entryValueReveal: false,
        lockOwnerReveal: false,
        pubSubPayloadReveal: false,
        batchEntryOperations: true,
        valueScan: true,
        cacheMetrics: true,
        ownerLockOperations: true,
      },
      limits: { maximumValueBytes: 1024, pubSubChannelMaxBytes: 49, pubSubPayloadMaxBytes: 7500 },
    };
    useSetupScopeStore.getState().select('primary-cache', capabilities);
    try {
      render(
        <MemoryRouter initialEntries={['/counters']}>
          <ManagementProvider store={store}><ManagementShell session={session} onLogout={() => Promise.resolve()} /></ManagementProvider>
        </MemoryRouter>,
      );

      expect(screen.getByRole('heading', { name: 'Counters unavailable' })).toBeVisible();
      expect(screen.getByText(/does not provide counter inspection/u)).toBeVisible();
      expect(screen.queryByRole('link', { name: 'Counters' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Create counter' })).not.toBeInTheDocument();
    } finally {
      useSetupScopeStore.getState().clear();
    }
  });
});
