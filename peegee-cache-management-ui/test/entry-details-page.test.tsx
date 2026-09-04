import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { EntryDetailsPage } from '@src/features/entries/EntryDetailsPage';
import { renderWithProviders } from './support/render';
import { entryMetadata, startEntryFixture, type EntryFixture } from './support/entry-fixture';

const metadata = entryMetadata;

describe('U4 entry details sensitive reveal lifecycle', () => {
  let fixture: EntryFixture;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    fixture = await startEntryFixture();
  });
  afterEach(async () => { await fixture.close(); });

  const renderDetails = (canReveal = true) => renderWithProviders(
    <EntryDetailsPage canReveal={canReveal} encodedKey={metadata.encodedKey} encodedNamespace={metadata.encodedNamespace} selectedSetupId="primary-cache" />,
    { store: fixture.store },
  );
  const reveals = () => fixture.requests((request) => request.method === 'POST' && request.path.endsWith('/value/reveal'));

  it('loads metadata only and does not offer reveal to viewers', async () => {
    renderDetails(false);
    expect(await screen.findByRole('heading', { name: metadata.key })).toBeVisible();
    expect(screen.getByText('Value hidden')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Reveal value' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('sensitive <value>');
    const metadataRequests = fixture.requests((request) => request.path.endsWith(`/entries/${metadata.encodedKey}`));
    expect(metadataRequests).toHaveLength(1);
    expect(metadataRequests[0]!.query.get('includeExpired')).toBe('true');
    expect(reveals()).toHaveLength(0);
  });

  it('reveals only after an explicit operator action, copies explicitly, and hides explicitly', async () => {
    const copied: string[] = [];
    const user = userEvent.setup();
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (value: string) => { copied.push(value); } },
    });
    renderDetails();

    await screen.findByRole('heading', { name: metadata.key });
    expect(copied).toEqual([]);
    expect(reveals()).toHaveLength(0);
    await user.type(screen.getByLabelText(/Reveal reason/u), 'incident review');
    await user.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    expect(reveals()).toHaveLength(1);
    expect(reveals()[0]!.path).toBe(`/api/v1/setups/primary-cache/namespaces/${metadata.encodedNamespace}/entries/${metadata.encodedKey}/value/reveal`);
    expect(reveals()[0]!.body).toEqual({ reason: 'incident review' });
    expect(reveals()[0]!.headers['x-peegeeq-csrf']).toBe('entry-pages-csrf-token-with-forty-three-characters');
    expect(window.location.href).not.toContain('sensitive');
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain('sensitive');
    expect(JSON.stringify(fixture.store.getState())).not.toContain('sensitive <value>');
    expect(copied).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Copy revealed value' }));
    expect(copied).toEqual(['sensitive <value>']);
    await user.click(screen.getByRole('button', { name: 'Hide value' }));
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
  });

  it('clears a revealed value on timeout, visibility loss, and route or setup changes', async () => {
    const user = userEvent.setup();
    fixture.state.autoHideAfterMillis = 25;
    const { rerender } = renderDetails();
    await screen.findByRole('heading', { name: metadata.key });
    await user.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    await waitFor(() => expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument());

    fixture.state.autoHideAfterMillis = 60_000;
    await user.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    let hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    act(() => { document.dispatchEvent(new globalThis.Event('visibilitychange')); });
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
    hidden = false;

    await user.click(screen.getByRole('button', { name: 'Reveal value' }));
    expect(await screen.findByText('sensitive <value>')).toBeVisible();
    rerender(<EntryDetailsPage canReveal encodedKey="differentEncodedKey" encodedNamespace={metadata.encodedNamespace} selectedSetupId="secondary-cache" />);
    expect(screen.queryByText('sensitive <value>')).not.toBeInTheDocument();
    expect(reveals()).toHaveLength(3);
  });
});
