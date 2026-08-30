import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { EntryDetailsClientPort } from '@src/api/inspection-client';
import type { EntryMetadata, RevealedEntryValue } from '@src/api/inspection-schemas';
import { EntryDetailsPage } from '@src/features/entries/EntryDetailsPage';

const metadata: EntryMetadata = {
  namespace: '客户/订单', encodedNamespace: '5a6i5oi3L-iureWNlQ',
  key: 'café/東京/🔒?x=1', encodedKey: 'Y2Fmw6kv5p2x5LqsL_CflJI_eD0x',
  valueType: 'STRING', sizeBytes: '17', version: '9007199254740993',
  createdAt: '2026-08-26T10:00:00Z', updatedAt: '2026-08-26T10:15:00Z',
  lastAccessedAt: null,
  ttl: { state: 'EXPIRING', ttlMillis: 45_000, expiresAt: '2026-08-26T10:15:45Z' },
};
const revealed: RevealedEntryValue = {
  key: metadata.key, version: metadata.version,
  value: { type: 'STRING', text: 'sensitive <value>' },
  revealedAt: '2026-08-26T10:15:30Z', autoHideAfterMillis: 1_000,
};

class FakeEntryDetailsClient implements EntryDetailsClientPort {
  readonly reveals: Array<{ setupId: string; namespace: string; key: string; reason?: string }> = [];

  constructor(private readonly autoHideAfterMillis = revealed.autoHideAfterMillis) {}

  async entries(): Promise<never> { throw new Error('not used'); }
  async entry(): Promise<EntryMetadata> { return metadata; }
  async revealEntryValue(setupId: string, namespace: string, key: string, reason?: string): Promise<RevealedEntryValue> {
    this.reveals.push({ setupId, namespace, key, reason });
    return { ...revealed, autoHideAfterMillis: this.autoHideAfterMillis };
  }
}

function renderDetails(client: EntryDetailsClientPort, canReveal = true) {
  return render(<MemoryRouter><EntryDetailsPage
    canReveal={canReveal} client={client} encodedKey={metadata.encodedKey}
    encodedNamespace={metadata.encodedNamespace} selectedSetupId="primary-cache"
  /></MemoryRouter>);
}

describe('U4 entry details sensitive reveal lifecycle', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => vi.useRealTimers());

  it('loads metadata only and does not offer reveal to viewers', async () => {
    renderDetails(new FakeEntryDetailsClient(), false);
    expect(await screen.findByRole('heading', { name: metadata.key })).toBeVisible();
    expect(screen.getByText('Value hidden')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Reveal value' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('sensitive <value>');
  });

  it('reveals only after an explicit operator action, copies explicitly, and hides explicitly', async () => {
    const client = new FakeEntryDetailsClient();
    const copied: string[] = [];
    const user = userEvent.setup();
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (value: string) => { copied.push(value); } },
    });
    renderDetails(client);

    await screen.findByRole('heading', { name: metadata.key });
    expect(copied).toEqual([]);
    await user.type(screen.getByLabelText(/Reveal reason/u), 'incident review');
    await user.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    expect(client.reveals).toEqual([{
      setupId: 'primary-cache', namespace: metadata.encodedNamespace,
      key: metadata.encodedKey, reason: 'incident review',
    }]);
    expect(window.location.href).not.toContain('sensitive');
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain('sensitive');
    expect(copied).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Copy revealed value' }));
    expect(copied).toEqual(['sensitive <value>']);
    await user.click(screen.getByRole('button', { name: 'Hide value' }));
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
  });

  it('clears a revealed value on timeout, visibility loss, and route or setup changes', async () => {
    const client = new FakeEntryDetailsClient(25);
    const { rerender } = renderDetails(client);
    await screen.findByRole('heading', { name: metadata.key });
    fireEvent.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    await waitFor(() => expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    let hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    act(() => document.dispatchEvent(new globalThis.Event('visibilitychange')));
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
    hidden = false;

    fireEvent.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    rerender(<MemoryRouter><EntryDetailsPage
      canReveal client={client} encodedKey="differentEncodedKey"
      encodedNamespace={metadata.encodedNamespace} selectedSetupId="secondary-cache"
    /></MemoryRouter>);
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
  });
});
