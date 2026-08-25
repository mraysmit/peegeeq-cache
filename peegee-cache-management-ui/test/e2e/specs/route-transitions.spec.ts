import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated } from '../management-test-support';

test.describe('every management route transition', () => {
  for (const source of managementRoutes) {
    for (const target of managementRoutes) {
      test(`${source.label} to ${target.label} preserves the authenticated shell`, async ({ page }) => {
        await openAuthenticated(page, source.path);
        await page.getByRole('link', { name: target.label, exact: true }).click();

        await expect(page.getByRole('heading', { name: target.label, exact: true })).toBeVisible();
        await expect(page.getByRole('link', { name: target.label, exact: true }))
          .toHaveAttribute('aria-current', 'page');
        await expect(page.getByText('local-operator', { exact: true })).toBeVisible();
        expect(new URL(page.url()).pathname).toBe(target.path === '/' ? '/ui' : `/ui${target.path}`);
      });
    }
  }
});
