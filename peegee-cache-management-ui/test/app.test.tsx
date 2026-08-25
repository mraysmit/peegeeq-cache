import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { ManagementShell } from '@src/app/ManagementShell';
import type { BrowserSession } from '@src/api/session-client';

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

describe('U1 authenticated management shell', () => {
  it('renders route navigation, identity, connection state, and role-aware controls', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <ManagementShell session={session} onLogout={() => Promise.resolve()} />
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
        <ManagementShell session={session} onLogout={() => Promise.resolve()} />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Use dark theme' }));
    await user.click(screen.getByRole('button', { name: 'Open notifications' }));

    expect(container.firstElementChild).toHaveAttribute('data-theme', 'dark');
    expect(screen.getByRole('complementary', { name: 'Notifications' })).toBeVisible();
    expect(container).not.toHaveTextContent('csrf-token-with-at-least-thirty-two-characters');
  });
});
