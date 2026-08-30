import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import type { BrowserSession } from '@src/api/session-client';
import { SettingsPage } from '@src/features/settings/SettingsPage';

const session: BrowserSession = { user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1.0', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true } };

describe('U8 Settings preferences', () => {
  beforeEach(() => localStorage.clear());

  it('offers every approved harmless preference and persists their allowlisted values', async () => {
    const user = userEvent.setup();
    render(<SettingsPage selectedSetupId="primary" session={session} />);

    await user.selectOptions(screen.getByLabelText('Byte display'), 'DECIMAL');
    await user.selectOptions(screen.getByLabelText('Masked-value auto-hide'), '120');
    expect(screen.getByText('Automatic bounded exponential backoff')).toBeVisible();

    expect(JSON.parse(localStorage.getItem('peegeeq.management.preferences') ?? '{}')).toEqual(expect.objectContaining({ byteUnits: 'DECIMAL', autoHideSeconds: 120 }));
  });
});
