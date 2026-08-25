import { expect, test, type Page, type Request, type Route } from '@playwright/test';

import {
  expectNoBrowserSecrets,
  openAuthenticated,
  problem,
  session,
} from '../management-test-support';

const primary = {
  setupId: 'primary-cache',
  displayName: 'Primary cache',
  host: 'db.example.test',
  port: 5432,
  database: 'peegeeq',
  schema: 'cache',
  sslMode: 'VERIFY_FULL',
  source: 'UI_SESSION',
  state: 'CONNECTED',
  schemaState: 'READY',
  lastHealth: {
    status: 'UP',
    latencyMillis: 7,
    checkedAt: '2099-01-01T00:00:00Z',
  },
} as const;

const analytics = {
  ...primary,
  setupId: 'analytics-cache',
  displayName: 'Analytics cache',
  database: 'analytics',
} as const;

const connectionTest = {
  databaseReachable: true,
  schemaState: 'READY',
  migrationVersion: '1',
  latencyMillis: 8,
  capabilities: {
    namespaceInspection: true,
    expiredEntryInspection: true,
    counterInspection: true,
    lockInspection: true,
    forcedLockRelease: true,
    bulkEntryDelete: true,
    bulkCounterDelete: true,
    pubSub: true,
    databaseStatistics: true,
    sensitiveValueReveal: true,
  },
  limits: { pubSubChannelMaxBytes: 63, pubSubPayloadMaxBytes: 8_000, maximumValueBytes: 1_000_000 },
} as const;

interface SetupApiState {
  setups: Array<Record<string, unknown>>;
  requests: Request[];
  listFailure?: boolean;
}

test.describe('functional setup management workflows', () => {
  test('loads real setup metadata and selects the active runtime scope', async ({ page }) => {
    await installSetupApi(page);
    await openSetupPage(page);

    const row = page.getByRole('row').filter({ hasText: 'Primary cache' });
    await expect(row).toContainText('db.example.test:5432/cache');
    await expect(row).toContainText('Connected');
    await expect(row).toContainText('7 ms');
    await row.getByRole('button', { name: 'Use setup' }).click();
    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: primary-cache');
    await expect(row.getByRole('button', { name: 'Selected' })).toBeDisabled();
  });

  test('renders the genuine first-run empty state', async ({ page }) => {
    await installSetupApi(page, []);
    await openSetupPage(page);

    await expect(page.getByRole('heading', { name: 'No setups registered' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Register the first setup' })).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
  });

  test('keeps viewer sessions read-only while retaining discovery and details', async ({ page }) => {
    await installSetupApi(page);
    await openSetupPage(page, { roles: ['viewer'] });

    await expect(page.getByText(/Viewer access is read-only/u)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Details' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Register setup' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Detach' })).toHaveCount(0);
  });

  test('loads detailed runtime metadata on demand', async ({ page }) => {
    await installSetupApi(page);
    await openSetupPage(page);
    await page.getByRole('button', { name: 'Details' }).click();

    const dialog = page.getByRole('dialog', { name: 'Setup details' });
    await expect(dialog).toContainText('Migration');
    await expect(dialog).toContainText('db.example.test:5432');
    await expect(dialog).toContainText('10');
    await dialog.getByRole('button', { name: 'Close details' }).click();
    await expect(dialog).toBeHidden();
  });

  test('tests and registers a TLS setup with CSRF protection and no browser secret leakage', async ({ page }) => {
    const password = 'browser-only-registration-password';
    const api = await installSetupApi(page, []);
    await openSetupPage(page);
    await page.getByRole('button', { name: /^Register setup$/u }).click();
    const dialog = page.getByRole('dialog', { name: 'Register setup' });

    await dialog.getByLabel('Setup ID').fill('analytics-cache');
    await dialog.getByLabel('Display name').fill('Analytics cache');
    await dialog.getByLabel('Host').fill('analytics.example.test');
    await dialog.getByLabel('Database').fill('analytics');
    await dialog.getByLabel('Schema').fill('cache');
    await dialog.getByLabel('Username').fill('cache-user');
    await dialog.getByLabel('Password').fill(password);
    await dialog.getByLabel('Trust profile').fill('production-ca');
    await dialog.getByRole('button', { name: 'Test connection' }).click();
    await expect(dialog.getByText(/Connection succeeded in 8 ms/u)).toBeVisible();
    await dialog.getByRole('button', { name: 'Register setup' }).click();

    await expect(page.getByText('Analytics cache').first()).toBeVisible();
    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: analytics-cache');
    const mutations = api.requests.filter((request) => request.method() === 'POST');
    expect(mutations).toHaveLength(2);
    expect(mutations.every((request) => request.headers()['x-peegeeq-csrf'] === 'c'.repeat(43))).toBe(true);
    expect(mutations.map((request) => request.postDataJSON() as Record<string, unknown>)).toEqual([
      expect.objectContaining({ host: 'analytics.example.test', password }),
      expect.objectContaining({ setupId: 'analytics-cache', password }),
    ]);
    await expectNoBrowserSecrets(page, [password]);
  });

  test('confirms detach and clears the active scope after the server accepts it', async ({ page }) => {
    const api = await installSetupApi(page);
    await openSetupPage(page);
    const row = page.getByRole('row').filter({ hasText: 'Primary cache' });
    await row.getByRole('button', { name: 'Use setup' }).click();
    await row.getByRole('button', { name: 'Detach' }).click();
    const dialog = page.getByRole('dialog', { name: 'Detach Primary cache?' });
    await expect(dialog).toContainText('database data will remain');
    await dialog.getByRole('button', { name: 'Detach' }).click();

    await expect(page.getByRole('status')).toHaveText('Primary cache was detached.');
    await expect(page.getByTitle('Active setup scope')).toHaveText('No setup selected');
    await expect(row.getByRole('button', { name: 'Connect' })).toBeVisible();
    const detachRequest = api.requests.find((request) => request.url().endsWith('/primary-cache/detach'));
    expect(detachRequest?.method()).toBe('POST');
    expect(detachRequest?.headers()['x-peegeeq-csrf']).toBe('c'.repeat(43));
  });

  test('requires confirmation before forgetting a session-owned registration', async ({ page }) => {
    const api = await installSetupApi(page);
    await openSetupPage(page);
    await page.getByRole('button', { name: 'Forget' }).click();
    const dialog = page.getByRole('dialog', { name: 'Forget Primary cache?' });
    await expect(dialog).toContainText('Database data is not deleted');
    await dialog.getByRole('button', { name: 'Forget' }).click();

    await expect(page.getByRole('heading', { name: 'No setups registered' })).toBeVisible();
    const forgetRequest = api.requests.find((request) => request.method() === 'DELETE');
    expect(forgetRequest?.headers()['x-peegeeq-csrf']).toBe('c'.repeat(43));
  });

  test('surfaces RFC 9457 list failures and recovers through refresh', async ({ page }) => {
    const api = await installSetupApi(page);
    api.listFailure = true;
    await openSetupPage(page);
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('SETUP_REGISTRY_UNAVAILABLE');
    await expect(alert).toContainText('setup-list-correlation');

    api.listFailure = false;
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect(page.getByText('Primary cache')).toBeVisible();
    await expect(alert).toHaveCount(0);
  });

  test('rejects malformed success payloads at the browser protocol boundary', async ({ page }) => {
    await page.route(/\/api\/v1\/setups(?:\?.*)?$/u, (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [{ ...primary, state: 'INVENTED' }] }),
    }));
    await openSetupPage(page);

    await expect(page.getByRole('alert')).toContainText('RESPONSE_CONTRACT_INVALID');
    await expect(page.getByRole('heading', { name: 'No setups registered' })).toHaveCount(0);
  });

  test('honors the server feature flag that disables setup registration', async ({ page }) => {
    await installSetupApi(page, []);
    await openSetupPage(page, {
      features: { ...session().features, setupRegistration: false },
    });

    await expect(page.getByText(/Setup registration is disabled/u)).toBeVisible();
    await expect(page.getByRole('button', { name: /Register/u })).toHaveCount(0);
  });

  test('hides navigation destinations unavailable to the selected setup', async ({ page }) => {
    await installSetupApi(page, [primary], {
      ...connectionTest.capabilities,
      counterInspection: false,
      lockInspection: false,
      pubSub: false,
    });
    await openSetupPage(page);
    await page.getByRole('button', { name: 'Use setup' }).click();

    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: primary-cache');
    await expect(page.getByRole('link', { name: 'Namespaces' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Keys' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Counters' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Locks' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Pub/Sub' })).toHaveCount(0);
  });

  test('persists only the allowlisted setup ID, revalidates capabilities, and clears scope on logout', async ({ page }) => {
    const passwordMarker = 'must-never-enter-scope-storage';
    await installSetupApi(page);
    await openSetupPage(page);
    await page.getByRole('button', { name: 'Use setup' }).click();

    const persisted = await page.evaluate(() => ({
      entries: Object.entries(sessionStorage),
      raw: sessionStorage.getItem('peegeeq-cache.scope.v1'),
    }));
    expect(persisted.entries).toHaveLength(1);
    expect(persisted.raw).toBe('{"setupId":"primary-cache"}');
    expect(JSON.stringify(persisted)).not.toContain(passwordMarker);

    await page.reload();
    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: primary-cache');
    await page.getByRole('button', { name: 'End local session' }).click();
    await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.length)).toBe(0);
  });

  test('invalidates a persisted namespace when the active setup changes', async ({ page }) => {
    await page.addInitScript(() => {
      sessionStorage.setItem(
        'peegeeq-cache.scope.v1',
        '{"setupId":"primary-cache","namespace":"orders/eu"}',
      );
    });
    await installSetupApi(page, [primary, analytics]);
    await openSetupPage(page);

    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: primary-cache');
    expect(await page.evaluate(() => sessionStorage.getItem('peegeeq-cache.scope.v1'))).toBe(
      '{"setupId":"primary-cache","namespace":"orders/eu"}',
    );

    const analyticsRow = page.getByRole('row').filter({ hasText: 'Analytics cache' });
    await analyticsRow.getByRole('button', { name: 'Use setup' }).click();

    await expect(page.getByTitle('Active setup scope')).toHaveText('Setup: analytics-cache');
    expect(await page.evaluate(() => sessionStorage.getItem('peegeeq-cache.scope.v1'))).toBe(
      '{"setupId":"analytics-cache"}',
    );
  });
});

async function openSetupPage(
  page: Page,
  overrides: Parameters<typeof openAuthenticated>[2] = {},
): Promise<void> {
  await openAuthenticated(page, '/setups', overrides, { setupApiInstalled: true });
}

async function installSetupApi(
  page: Page,
  initialSetups: Array<Record<string, unknown>> = [primary],
  capabilities: Record<string, boolean> = connectionTest.capabilities,
): Promise<SetupApiState> {
  const state: SetupApiState = { setups: initialSetups.map((setup) => ({ ...setup })), requests: [] };
  await page.route(/\/api\/v1\/setups(?:\/.*)?(?:\?.*)?$/u, async (route: Route) => {
    const request = route.request();
    state.requests.push(request);
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET' && path === '/api/v1/setups') {
      if (state.listFailure) {
        await json(route, 503, problem(
          503,
          'SETUP_REGISTRY_UNAVAILABLE',
          'Setup discovery is temporarily unavailable',
          'setup-list-correlation',
        ));
      } else {
        await json(route, 200, { items: state.setups });
      }
      return;
    }
    if (request.method() === 'GET' && path === '/api/v1/setups/primary-cache') {
      await json(route, 200, {
        setup: state.setups[0],
        migrationVersion: '1',
        runtime: {
          defaultTtlMillis: null,
          expirySweeperEnabled: true,
          expirySweepIntervalMillis: 30_000,
          expirySweepBatchSize: 500,
          poolMaxSize: 10,
        },
        registeredAt: '2099-01-01T00:00:00Z',
        connectedAt: '2099-01-01T00:01:00Z',
      });
      return;
    }
    if (request.method() === 'GET' && path.endsWith('/health')) {
      await json(route, 200, {
        status: 'UP',
        schemaReady: true,
        latencyMillis: 6,
        checkedAt: '2099-01-01T00:00:00Z',
        detail: 'PostgreSQL and cache schema are ready',
      });
      return;
    }
    if (request.method() === 'GET' && path.endsWith('/capabilities')) {
      await json(route, 200, {
        migrationVersion: '1',
        capabilities,
        limits: connectionTest.limits,
      });
      return;
    }
    if (request.method() === 'POST' && (path.endsWith('/actions/test') || path.endsWith('/test'))) {
      await json(route, 200, connectionTest);
      return;
    }
    if (request.method() === 'POST' && path === '/api/v1/setups') {
      const body = request.postDataJSON() as Record<string, unknown>;
      const created = {
        ...primary,
        setupId: body.setupId,
        displayName: body.displayName,
        host: body.host,
        database: body.database,
        schema: body.schema,
      };
      state.setups = [created];
      await json(route, 201, created);
      return;
    }
    if (request.method() === 'POST' && path.endsWith('/detach')) {
      state.setups = state.setups.map((setup) => ({ ...setup, state: 'DETACHED' }));
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    if (request.method() === 'POST' && path.endsWith('/connect')) {
      state.setups = state.setups.map((setup) => ({ ...setup, state: 'CONNECTED' }));
      await json(route, 200, state.setups[0] ?? primary);
      return;
    }
    if (request.method() === 'DELETE') {
      state.setups = [];
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fulfill({ status: 404, body: '' });
  });
  return state;
}

async function json(route: Route, status: number, body: unknown): Promise<void> {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
    headers: { 'cache-control': 'no-store' },
  });
}
