import { expect, test, type Page, type Route } from '@playwright/test';

import { openAuthenticated, problem } from '../management-test-support';

const capabilities = {
  migrationVersion: '1',
  capabilities: {
    namespaceInspection: true,
    expiredEntryInspection: true,
    counterInspection: true,
    lockInspection: true,
    forcedLockRelease: true,
    bulkEntryDelete: true,
    bulkCounterDelete: true,
    pubSub: true,
    databaseStatistics: false,
    sensitiveValueReveal: true,
  },
  limits: { pubSubChannelMaxBytes: 63, pubSubPayloadMaxBytes: 8_000, maximumValueBytes: 1_000_000 },
} as const;

const initialOverview = {
  scope: 'DATABASE',
  observedAt: '2026-08-26T10:15:30Z',
  health: {
    status: 'UP', schemaReady: true, latencyMillis: 7,
    checkedAt: '2026-08-26T10:15:29Z', detail: 'Ready',
  },
  totals: {
    namespaceCount: '1', liveEntryCount: '9007199254740993', liveCounterCount: '3',
    activeLockCount: '1', expiredEntryCount: '2',
    schemaBytes: { availability: 'UNAVAILABLE', reason: 'pg_total_relation_size permission denied', value: null },
  },
  expiry: {
    oldestExpiredRowLagMillis: 2_500, sweeperEnabled: true,
    lastSweepAt: '2026-08-26T10:15:00Z', lastSweepDeletedRows: '8',
  },
  valueTypeCounts: { STRING: '8', JSON: '2' },
  topNamespaces: [{
    namespace: 'orders/eu', encodedNamespace: 'b3JkZXJzL2V1',
    liveEntryCount: '10', liveCounterCount: '3', activeLockCount: '1',
    expiringEntryCount: '4', expiredEntryCount: '2', estimatedStorageBytes: '4096',
    observedAt: '2026-08-26T10:15:30Z',
  }],
} as const;

interface OverviewApiState {
  fail: boolean;
  overview: Record<string, unknown>;
}

test.describe('U3 database overview workflows', () => {
  test('renders precise database truth and permission-aware unavailable statistics', async ({ page }) => {
    await installSelectedScope(page);
    await installOverviewApi(page);
    await openAuthenticated(page, '/', {}, { setupApiInstalled: true });

    await expect(page.getByText('Database-wide snapshot')).toBeVisible();
    await expect(page.getByText('9,007,199,254,740,993')).toBeVisible();
    await expect(page.getByText('Unavailable')).toBeVisible();
    await expect(page.getByText('pg_total_relation_size permission denied')).toBeVisible();
    await expect(page.getByText('orders/eu')).toBeVisible();
    await expect(page.getByText('Management server activity')).toBeVisible();
    await expect(page.getByLabel('Snapshot observed at')).toHaveAttribute(
      'datetime',
      initialOverview.observedAt,
    );
  });

  test('keeps an observed snapshot visibly stale through interruption and validates recovery', async ({ page }) => {
    await installSelectedScope(page);
    const api = await installOverviewApi(page);
    await openAuthenticated(page, '/', {}, { setupApiInstalled: true });
    await expect(page.getByText('9,007,199,254,740,993')).toBeVisible();

    api.fail = true;
    await page.getByRole('button', { name: 'Refresh overview' }).click();
    await expect(page.getByRole('alert')).toContainText('Stale data');
    await expect(page.getByRole('alert')).toContainText('overview-correlation');
    await expect(page.getByText('9,007,199,254,740,993')).toBeVisible();

    api.fail = false;
    api.overview = { ...initialOverview, observedAt: '2026-08-26T10:16:30Z' };
    await page.getByRole('button', { name: 'Refresh overview' }).click();
    await expect(page.getByLabel('Snapshot observed at')).toHaveAttribute(
      'datetime',
      '2026-08-26T10:16:30Z',
    );
    await expect(page.getByRole('alert')).toHaveCount(0);
  });
});

async function installSelectedScope(page: Page): Promise<void> {
  await page.addInitScript(() => {
    window.sessionStorage.setItem('peegeeq-cache.scope.v1', JSON.stringify({ setupId: 'primary-cache' }));
  });
}

async function installOverviewApi(page: Page): Promise<OverviewApiState> {
  const state: OverviewApiState = { fail: false, overview: initialOverview };
  await page.route(/\/api\/v1\/setups\/primary-cache\/capabilities(?:\?.*)?$/u, async (route) => {
    await fulfill(route, 200, capabilities);
  });
  await page.route(/\/api\/v1\/setups\/primary-cache\/overview(?:\?.*)?$/u, async (route) => {
    if (state.fail) {
      await fulfill(route, 503, problem(503, 'DATABASE_UNAVAILABLE', 'Database unavailable', 'overview-correlation'));
      return;
    }
    await fulfill(route, 200, state.overview);
  });
  return state;
}

async function fulfill(route: Route, status: number, body: unknown): Promise<void> {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    headers: { 'cache-control': 'no-store' },
    body: JSON.stringify(body),
  });
}
