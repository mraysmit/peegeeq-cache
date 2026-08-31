import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import type { EntryAdministrationClientPort } from '@src/api/entry-administration-client';
import type { BulkDeletePreview, BulkDeleteResult, ConfirmedEntryDelete, EntryDeleteSelection, EntrySetBody, EntrySetResult } from '@src/api/entry-administration-schemas';
import type { EntryClientPort, EntryDetailsClientPort } from '@src/api/inspection-client';
import type { EntryMetadata, EntryPage, RevealedEntryValue } from '@src/api/inspection-schemas';
import { ManagementClientError } from '@src/api/session-client';
import { EntriesPage } from '@src/features/entries/EntriesPage';
import { EntryDetailsPage } from '@src/features/entries/EntryDetailsPage';

const metadata: EntryMetadata = {
  namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'order:1', encodedKey: 'b3JkZXI6MQ',
  valueType: 'STRING', sizeBytes: '5', version: '3',
  createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', lastAccessedAt: null,
  ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null },
};

class FakeInspection implements EntryDetailsClientPort {
  reads = 0;
  async entries(): Promise<EntryPage> { return { items: [metadata], nextCursor: null, hasMore: false }; }
  async entry(): Promise<EntryMetadata> { this.reads++; return metadata; }
  async revealEntryValue(): Promise<RevealedEntryValue> { throw new Error('not used'); }
}

class FakeAdministration implements EntryAdministrationClientPort {
  sets: Array<{ body: EntrySetBody; version?: string }> = [];
  deletes: string[] = [];
  previews: EntryDeleteSelection[] = [];
  confirmations: ConfirmedEntryDelete[] = [];
  conflict = false;

  async setEntry(_setup: string, _namespace: string, _key: string, body: EntrySetBody, version?: string): Promise<EntrySetResult> {
    this.sets.push({ body, version });
    if (this.conflict) throw new ManagementClientError(412, 'VERSION_MISMATCH', 'The entry changed');
    return { applied: true, created: false, version: '4', updatedAt: '2026-08-29T10:02:00Z', ttl: metadata.ttl };
  }
  async expireEntry(): Promise<EntryMetadata> { return { ...metadata, ttl: { state: 'EXPIRING', ttlMillis: 60_000, expiresAt: '2026-08-29T10:02:00Z' } }; }
  async persistEntry(): Promise<EntryMetadata> { return metadata; }
  async touchEntry(): Promise<EntryMetadata> { return { ...metadata, lastAccessedAt: '2026-08-29T10:01:30Z' }; }
  async deleteEntry(_setup: string, _namespace: string, _key: string, version: string): Promise<void> { this.deletes.push(version); }
  async previewBulkDelete(_setup: string, _namespace: string, selection: EntryDeleteSelection): Promise<BulkDeletePreview> {
    this.previews.push(selection);
    return { previewToken: 'p'.repeat(32), expiresAt: '2099-08-29T10:05:00Z', setupId: 'primary-cache', namespace: 'orders', resolvedCount: '1', totalBytes: '5', sampleKeys: ['order:1'], confirmationPhrase: 'DELETE 1 ENTRIES' };
  }
  async executeBulkDelete(_setup: string, _namespace: string, confirmation: ConfirmedEntryDelete): Promise<BulkDeleteResult> {
    this.confirmations.push(confirmation);
    return { processedCount: '1', deletedCount: '1', conflictCount: '0', missingCount: '0', failedCount: '0', conflicts: [] };
  }
}

function details(inspection: FakeInspection, administration: FakeAdministration) {
  return render(<MemoryRouter><EntryDetailsPage
    administrationClient={administration} canOperate canReveal={false} client={inspection}
    encodedKey={metadata.encodedKey} encodedNamespace={metadata.encodedNamespace}
    selectedSetupId="primary-cache"
  /></MemoryRouter>);
}

describe('U5 entry administration pages', () => {
  it('renders viewer entry inventory and details without mutation or reveal controls', async () => {
    const inspection = new FakeInspection();
    const administration = new FakeAdministration();
    const inventory = render(<MemoryRouter><EntriesPage
      administrationClient={administration} canOperate={false} client={inspection}
      selectedNamespace="orders" selectedSetupId="primary-cache"
    /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: metadata.key })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Entry results' })).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: 'Create entry' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(`Select ${metadata.key}`)).not.toBeInTheDocument();
    inventory.unmount();

    render(<MemoryRouter><EntryDetailsPage
      administrationClient={administration} canOperate={false} canReveal={false} client={inspection}
      encodedKey={metadata.encodedKey} encodedNamespace={metadata.encodedNamespace}
      selectedSetupId="primary-cache"
    /></MemoryRouter>);
    expect(await screen.findByText('Value hidden')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Edit entry' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reveal value' })).not.toBeInTheDocument();
  });

  it('keeps single-entry administration while a setup disables bulk deletion', async () => {
    render(<MemoryRouter><EntriesPage
      administrationClient={new FakeAdministration()} canBulkDelete={false} canOperate client={new FakeInspection()}
      selectedNamespace="orders" selectedSetupId="primary-cache"
    /></MemoryRouter>);
    expect(await screen.findByRole('button', { name: 'Create entry' })).toBeVisible();
    expect(screen.queryByLabelText(`Select ${metadata.key}`)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview selected deletion' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview matching-filter deletion' })).not.toBeInTheDocument();
  });

  it('defaults existing edits to observed-version CAS and presents the committed server result', async () => {
    const inspection = new FakeInspection();
    const administration = new FakeAdministration();
    const user = userEvent.setup();
    details(inspection, administration);
    await screen.findByRole('heading', { name: metadata.key });

    await user.click(screen.getByRole('button', { name: 'Edit entry' }));
    expect(screen.getByLabelText('Set mode')).toHaveValue('ONLY_IF_VERSION_MATCHES');
    await user.type(screen.getByLabelText('Entry value'), 'replacement');
    await user.click(screen.getByRole('button', { name: 'Save entry' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Entry updated at');
    expect(screen.getByRole('status')).toHaveTextContent('version 4');
    expect(administration.sets).toEqual([{ body: {
      value: { type: 'STRING', text: 'replacement' }, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null,
      setMode: 'ONLY_IF_VERSION_MATCHES',
    }, version: '3' }]);
  });

  it('preserves edit input and reloads metadata after a CAS conflict', async () => {
    const inspection = new FakeInspection();
    const administration = new FakeAdministration();
    administration.conflict = true;
    const user = userEvent.setup();
    details(inspection, administration);
    await screen.findByRole('heading', { name: metadata.key });
    await user.click(screen.getByRole('button', { name: 'Edit entry' }));
    await user.type(screen.getByLabelText('Entry value'), 'keep this input');
    await user.click(screen.getByRole('button', { name: 'Save entry' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('VERSION_MISMATCH');
    expect(screen.getByLabelText('Entry value')).toHaveValue('keep this input');
    await waitFor(() => expect(inspection.reads).toBeGreaterThan(1));
  });

  it('uses authoritative metadata for TTL/touch and exact observed version for deletion', async () => {
    const inspection = new FakeInspection();
    const administration = new FakeAdministration();
    const user = userEvent.setup();
    details(inspection, administration);
    await screen.findByRole('heading', { name: metadata.key });

    await user.type(screen.getByLabelText('TTL milliseconds'), '60000');
    await user.click(screen.getByRole('button', { name: 'Set TTL' }));
    expect(await screen.findByText('60 s')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Touch entry' }));
    expect((await screen.findByText(/Last accessed/u)).parentElement).toHaveTextContent('2026');

    await user.click(screen.getByRole('button', { name: 'Delete entry' }));
    await user.type(screen.getByLabelText('Confirm entry key'), metadata.key);
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry deleted');
    expect(administration.deletes).toEqual(['3']);
  });

  it('previews selected exact-version targets and executes only the typed server phrase', async () => {
    const administration = new FakeAdministration();
    const inspection: EntryClientPort = new FakeInspection();
    const user = userEvent.setup();
    render(<MemoryRouter><EntriesPage
      administrationClient={administration} canOperate client={inspection}
      selectedNamespace="orders" selectedSetupId="primary-cache"
    /></MemoryRouter>);
    await screen.findByRole('link', { name: metadata.key });
    await user.click(screen.getByRole('checkbox', { name: `Select ${metadata.key}` }));
    await user.click(screen.getByRole('button', { name: 'Preview selected deletion' }));

    expect(await screen.findByRole('dialog', { name: 'Confirm bulk entry deletion' })).toHaveTextContent('DELETE 1 ENTRIES');
    expect(administration.previews).toEqual([{ selection: { type: 'EXPLICIT', targets: [{ key: metadata.key, version: metadata.version }] } }]);
    expect(screen.getByRole('button', { name: 'Delete previewed entries' })).toBeDisabled();
    await user.type(screen.getByLabelText('Type confirmation phrase'), 'DELETE 1 ENTRIES');
    await user.click(screen.getByRole('button', { name: 'Delete previewed entries' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Deleted 1 of 1 previewed entries');
    expect(administration.confirmations).toEqual([{ previewToken: 'p'.repeat(32), confirmationPhrase: 'DELETE 1 ENTRIES' }]);
  });
});
