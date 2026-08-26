import { expect, test, type Page, type Request, type Route } from '@playwright/test';

import { openAuthenticated } from '../management-test-support';

const capabilities = {
  migrationVersion: '1',
  capabilities: {
    namespaceInspection: true, expiredEntryInspection: true, counterInspection: true,
    lockInspection: true, forcedLockRelease: true, bulkEntryDelete: true,
    bulkCounterDelete: true, pubSub: true, databaseStatistics: true, sensitiveValueReveal: true,
  },
  limits: { pubSubChannelMaxBytes: 63, pubSubPayloadMaxBytes: 8_000, maximumValueBytes: 1_000_000 },
};

const namespace = {
  namespace: 'orders/eu', encodedNamespace: 'b3JkZXJzL2V1',
  liveEntryCount: '10', liveCounterCount: '3', activeLockCount: '1',
  expiringEntryCount: '4', expiredEntryCount: '2', estimatedStorageBytes: '4096',
  observedAt: '2026-08-26T10:15:30Z',
};

test.describe('U3 namespace browsing workflows', () => {
  test('filters and traverses opaque cursor pages without reconstructing cursor state', async ({ page }) => {
    const requests = await installNamespaceApi(page);
    await openNamespaces(page);
    await expect(page.getByText('orders/eu')).toBeVisible();

    await page.getByLabel('Namespace prefix').fill('orders');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await page.getByRole('button', { name: 'Next page' }).click();
    await expect(page.getByText('orders/us')).toBeVisible();
    await page.getByRole('button', { name: 'Previous page' }).click();
    await expect(page.getByText('orders/eu')).toBeVisible();

    const listUrls = requests.filter((request) => !request.url().includes('/export'))
      .map((request) => new URL(request.url()).searchParams.get('cursor'));
    expect(listUrls).toContain('opaque-cursor-2');
    expect(listUrls.at(-1)).toBeNull();
  });

  test('downloads validated server export and moves details into namespace scope', async ({ page }) => {
    await installNamespaceApi(page);
    await openNamespaces(page);

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export namespaces' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('primary-cache-namespaces.json');
    await expect(page.getByRole('status')).toHaveText('Exported 1 namespace.');

    await page.getByRole('link', { name: 'orders/eu' }).click();
    await expect(page.getByRole('heading', { name: 'orders/eu' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Entries' })).toBeVisible();
    const scope = await page.evaluate(() => window.sessionStorage.getItem('peegeeq-cache.scope.v1'));
    expect(scope).toBe(JSON.stringify({ setupId: 'primary-cache', namespace: 'orders/eu' }));
  });
});

async function openNamespaces(page: Page): Promise<void> {
  await page.addInitScript(() => {
    window.sessionStorage.setItem('peegeeq-cache.scope.v1', JSON.stringify({ setupId: 'primary-cache' }));
  });
  await openAuthenticated(page, '/namespaces', {}, { setupApiInstalled: true });
}

async function installNamespaceApi(page: Page): Promise<Request[]> {
  const requests: Request[] = [];
  await page.route(/\/api\/v1\/setups\/primary-cache\/capabilities(?:\?.*)?$/u, async (route) => fulfill(route, capabilities));
  await page.route(/\/api\/v1\/setups\/primary-cache\/namespaces(?:\/.*)?(?:\?.*)?$/u, async (route) => {
    const request = route.request();
    requests.push(request);
    const url = new URL(request.url());
    if (url.pathname.endsWith('/export')) {
      await fulfill(route, { items: [namespace], truncated: false, exportedAt: '2026-08-26T10:16:00Z' });
      return;
    }
    if (url.pathname.endsWith(`/${namespace.encodedNamespace}`)) {
      await fulfill(route, {
        stats: namespace,
        valueTypeCounts: { STRING: '7', JSON: '3' },
        ttlDistribution: [{ range: 'PERSISTENT', count: '6' }],
      });
      return;
    }
    const cursor = url.searchParams.get('cursor');
    await fulfill(route, {
      items: [{ ...namespace, namespace: cursor === null ? 'orders/eu' : 'orders/us' }],
      nextCursor: cursor === null ? 'opaque-cursor-2' : null,
      hasMore: cursor === null,
    });
  });
  return requests;
}

async function fulfill(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(body) });
}
