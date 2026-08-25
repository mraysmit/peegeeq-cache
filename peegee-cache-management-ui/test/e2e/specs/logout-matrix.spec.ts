import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated, problem } from '../management-test-support';

const failureStatuses = [
  400, 402, 403, 404, 405, 408, 409, 410, 412, 413,
  415, 422, 423, 424, 429, 500, 502, 503, 504, 507,
] as const;

test.describe('local logout failure and recovery matrix', () => {
  for (const status of failureStatuses) {
    test(`logout HTTP ${status} retains the shell and allows retry`, async ({ page }) => {
      const routes = await openAuthenticated(page);
      routes.delete = {
        status,
        body: problem(status, `LOGOUT_FAILURE_${status}`, `Logout failed ${status}`, `logout-${status}`),
      };
      const logout = page.getByRole('button', { name: 'End local session' });
      await logout.click();

      await expect(page.getByRole('alert')).toContainText(`LOGOUT_FAILURE_${status}`);
      await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
      await expect(logout).toBeEnabled();

      routes.delete = { status: 204 };
      await logout.click();
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
    });
  }
});

test.describe('local logout terminal outcomes', () => {
  for (const [index, route] of managementRoutes.slice(0, 5).entries()) {
    test(`204 termination clears the shell from ${route.label}`, async ({ page }) => {
      const routes = await openAuthenticated(page, route.path);
      routes.delete = { status: 204 };
      await page.getByRole('button', { name: 'End local session' }).click();
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
      expect(routes.requests.filter((request) => request.method() === 'DELETE')).toHaveLength(1);
      expect(index).toBeGreaterThanOrEqual(0);
    });

    test(`401 termination clears stale client state from ${route.label}`, async ({ page }) => {
      const routes = await openAuthenticated(page, route.path);
      routes.delete = {
        status: 401,
        body: problem(401, 'SESSION_EXPIRED', 'The session has already expired', `stale-${index}`),
      };
      await page.getByRole('button', { name: 'End local session' }).click();
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    });
  }
});
