import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated } from '../management-test-support';

const scenarios = ['toggle button', 'close action', 'route persistence', 'theme coexistence'] as const;

test.describe('notification drawer on every route', () => {
  for (const route of managementRoutes) {
    for (const scenario of scenarios) {
      test(`${route.label}: notifications ${scenario}`, async ({ page }) => {
        await openAuthenticated(page, route.path);
        const open = page.getByRole('button', { name: 'Open notifications' });
        await expect(open).toHaveAttribute('aria-expanded', 'false');
        await open.click();

        const drawer = page.getByRole('complementary', { name: 'Notifications' });
        await expect(drawer).toBeVisible();
        await expect(drawer).toContainText('No management notifications.');
        await expect(page.getByRole('button', { name: 'Close notifications' }).first())
          .toHaveAttribute('aria-expanded', 'true');

        if (scenario === 'toggle button') {
          await page.getByRole('button', { name: 'Close notifications' }).first().click();
          await expect(drawer).toHaveCount(0);
        } else if (scenario === 'close action') {
          await drawer.getByRole('button', { name: 'Close notifications' }).click();
          await expect(drawer).toHaveCount(0);
          await expect(page.getByRole('button', { name: 'Open notifications' }))
            .toHaveAttribute('aria-expanded', 'false');
        } else if (scenario === 'route persistence') {
          const destination = route.label === 'Monitoring' ? 'Overview' : 'Monitoring';
          await page.getByRole('link', { name: destination, exact: true }).click();
          await expect(drawer).toBeVisible();
        } else {
          await page.getByRole('button', { name: 'Use dark theme' }).click();
          await expect(drawer).toBeVisible();
          await expect(page.locator('.console')).toHaveAttribute('data-theme', 'dark');
        }
      });
    }
  }
});
