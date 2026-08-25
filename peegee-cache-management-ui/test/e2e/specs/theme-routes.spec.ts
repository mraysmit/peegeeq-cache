import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated } from '../management-test-support';

const scenarios = ['activate dark', 'round trip to light', 'survive navigation', 'reset on reload'] as const;

test.describe('theme controls on every route', () => {
  for (const route of managementRoutes) {
    for (const scenario of scenarios) {
      test(`${route.label}: ${scenario}`, async ({ page }) => {
        await openAuthenticated(page, route.path);
        const consoleRoot = page.locator('.console');
        const darkButton = page.getByRole('button', { name: 'Use dark theme' });

        await expect(consoleRoot).toHaveAttribute('data-theme', 'light');
        await darkButton.click();
        await expect(consoleRoot).toHaveAttribute('data-theme', 'dark');
        await expect(page.getByRole('button', { name: 'Use light theme' })).toBeVisible();

        if (scenario === 'round trip to light') {
          await page.getByRole('button', { name: 'Use light theme' }).click();
          await expect(consoleRoot).toHaveAttribute('data-theme', 'light');
        } else if (scenario === 'survive navigation') {
          const destination = route.label === 'Settings' ? 'Overview' : 'Settings';
          await page.getByRole('link', { name: destination, exact: true }).click();
          await expect(consoleRoot).toHaveAttribute('data-theme', 'dark');
        } else if (scenario === 'reset on reload') {
          await page.reload();
          await expect(consoleRoot).toHaveAttribute('data-theme', 'light');
        }
      });
    }
  }
});
