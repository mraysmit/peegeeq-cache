import { expect, type Page, type Request, type Route } from '@playwright/test';

export interface SessionContract {
  user: string;
  roles: Array<'viewer' | 'operator'>;
  serverVersion: string;
  apiVersion: 'v1';
  authenticationMode: 'LOCAL_TOKEN' | 'TRUSTED_PROXY';
  csrfToken: string;
  sessionIdleExpiresAt: string;
  sessionExpiresAt: string;
  features: {
    setupRegistration: boolean;
    sensitiveReveal: boolean;
  };
}

export interface ProblemContract {
  type: string;
  title: string;
  status: number;
  code: string;
  detail: string;
  instance: string;
  correlationId: string;
  fieldErrors: Array<{ field: string; message: string }>;
}

export interface SessionRoutes {
  get?: { status: number; body: SessionContract | ProblemContract | Record<string, unknown> };
  post?: { status: number; body: SessionContract | ProblemContract | Record<string, unknown> };
  delete?: { status: number; body?: ProblemContract | Record<string, unknown> };
  requests: Request[];
}

export interface ManagementRoute {
  label: string;
  path: string;
}

export const managementRoutes: readonly ManagementRoute[] = [
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

export function session(overrides: Partial<SessionContract> = {}): SessionContract {
  return {
    user: 'local-operator',
    roles: ['viewer', 'operator'],
    serverVersion: '0.1.0-SNAPSHOT',
    apiVersion: 'v1',
    authenticationMode: 'LOCAL_TOKEN',
    csrfToken: 'c'.repeat(43),
    sessionIdleExpiresAt: '2099-08-25T09:00:00Z',
    sessionExpiresAt: '2099-08-25T16:00:00Z',
    features: {
      setupRegistration: true,
      sensitiveReveal: true,
    },
    ...overrides,
  };
}

export function problem(
  status: number,
  code = `TEST_${status}`,
  detail = `Injected management failure ${status}`,
  correlationId = `corr-${status}`,
): ProblemContract {
  return {
    type: `https://peegeeq.dev/problems/test-${status}`,
    title: `Test failure ${status}`,
    status,
    code,
    detail,
    instance: '/api/v1/session',
    correlationId,
    fieldErrors: [],
  };
}

export async function installSessionRoutes(
  page: Page,
  plan: Omit<SessionRoutes, 'requests'> = {},
): Promise<SessionRoutes> {
  const configured: SessionRoutes = {
    get: plan.get ?? { status: 200, body: session() },
    post: plan.post ?? { status: 200, body: session() },
    delete: plan.delete ?? { status: 204 },
    requests: [],
  };

  await page.route(/\/api\/v1\/session(?:\?.*)?$/, async (route: Route) => {
    const request = route.request();
    configured.requests.push(request);
    if (request.method() !== 'GET') {
      await route.fulfill({ status: 405, body: '' });
      return;
    }
    const response = configured.get ?? { status: 500, body: problem(500) };
    await fulfillJson(route, response.status, response.body);
  });

  await page.route(/\/api\/v1\/session\/local(?:\?.*)?$/, async (route: Route) => {
    const request = route.request();
    configured.requests.push(request);
    if (request.method() === 'POST') {
      const response = configured.post ?? { status: 500, body: problem(500) };
      await fulfillJson(route, response.status, response.body);
      return;
    }
    if (request.method() === 'DELETE') {
      const response = configured.delete ?? { status: 204 };
      if (response.status === 204) {
        await route.fulfill({ status: 204, body: '' });
      } else {
        await fulfillJson(route, response.status, response.body ?? problem(response.status));
      }
      return;
    }
    await route.fulfill({ status: 405, body: '' });
  });
  return configured;
}

export async function openAuthenticated(
  page: Page,
  path = '/',
  overrides: Partial<SessionContract> = {},
  options: { setupApiInstalled?: boolean } = {},
): Promise<SessionRoutes> {
  if (!options.setupApiInstalled) {
    await page.route(/\/api\/v1\/setups(?:\?.*)?$/u, async (route) => {
      await fulfillJson(route, 200, { items: [] });
    });
  }
  const routes = await installSessionRoutes(page, {
    get: { status: 200, body: session(overrides) },
  });
  await page.goto(uiPath(path));
  await expect(page.getByRole('heading', { name: headingFor(path), exact: true })).toBeVisible();
  return routes;
}

export async function openLogin(
  page: Page,
  post: { status: number; body: SessionContract | ProblemContract | Record<string, unknown> },
): Promise<SessionRoutes> {
  const routes = await installSessionRoutes(page, {
    get: { status: 401, body: problem(401, 'AUTHENTICATION_REQUIRED') },
    post,
  });
  await page.goto('/ui/');
  await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
  return routes;
}

export function uiPath(path: string): string {
  if (path.startsWith('/ui')) return path;
  return path === '/' ? '/ui/' : `/ui${path}`;
}

export function headingFor(path: string): string {
  const pathname = path.split(/[?#]/u, 1)[0]?.replace(/\/$/u, '') || '/';
  return managementRoutes.find((candidate) => candidate.path === pathname)?.label ?? 'Overview';
}

export async function expectNoBrowserSecrets(page: Page, secrets: readonly string[]): Promise<void> {
  const snapshot = await page.evaluate(() => ({
    url: window.location.href,
    html: document.documentElement.outerHTML,
    local: Object.values(localStorage),
    session: Object.values(sessionStorage),
  }));
  for (const secret of secrets) {
    expect(snapshot.url).not.toContain(secret);
    expect(snapshot.html).not.toContain(secret);
    expect(snapshot.local.join('\n')).not.toContain(secret);
    expect(snapshot.session.join('\n')).not.toContain(secret);
  }
}

async function fulfillJson(
  route: Route,
  status: number,
  body: SessionContract | ProblemContract | Record<string, unknown>,
): Promise<void> {
  await route.fulfill({
    status,
    contentType: status >= 400 ? 'application/problem+json' : 'application/json',
    body: JSON.stringify(body),
    headers: { 'cache-control': 'no-store' },
  });
}
