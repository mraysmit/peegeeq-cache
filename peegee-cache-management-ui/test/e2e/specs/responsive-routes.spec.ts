import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated } from '../management-test-support';

const viewports = [
  { name: 'small phone', width: 320, height: 568, compact: true },
  { name: 'phone', width: 390, height: 844, compact: true },
  { name: 'large phone', width: 430, height: 932, compact: true },
  { name: 'tablet portrait', width: 760, height: 1024, compact: true },
  { name: 'tablet landscape', width: 1024, height: 768, compact: false },
  { name: 'desktop', width: 1440, height: 900, compact: false },
] as const;

test.describe('responsive management routes', () => {
  for (const route of managementRoutes) {
    for (const viewport of viewports) {
      test(`${route.label} is usable at ${viewport.name}`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height });
        await openAuthenticated(page, route.path);

        const navigation = page.getByRole('navigation', { name: 'Management sections' });
        await expect(navigation).toBeVisible();
        await expect(page.getByRole('heading', { name: route.label, exact: true })).toBeVisible();
        await expect(page.getByLabel('Session and connection status')).toBeVisible();
        expect(await navigation.evaluate((element) => window.getComputedStyle(element).flexDirection))
          .toBe(viewport.compact ? 'row' : 'column');
        expect(await page.evaluate(() => document.body.scrollWidth <= window.innerWidth)).toBe(true);
      });
    }
  }
});
