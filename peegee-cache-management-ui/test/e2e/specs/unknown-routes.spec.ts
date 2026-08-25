import { expect, test } from '@playwright/test';

import { openAuthenticated } from '../management-test-support';

const unknownPaths = [
  '/missing',
  '/unknown/child',
  '/setups/missing',
  '/namespaces/missing/child',
  '/keys/missing',
  '/counters/missing',
  '/locks/missing',
  '/pubsub/missing',
  '/monitoring/missing',
  '/settings/missing',
  '/UPPERCASE',
  '/Overview',
  '/setups-legacy',
  '/namespace',
  '/key',
  '/counter',
  '/lock',
  '/pub-sub',
  '/monitor',
  '/settings/advanced',
  '/%E6%9D%B1%E4%BA%AC',
  '/unknown%20route',
  '/a/b/c/d/e',
  '/404',
  '/0',
  '/false',
  '/null',
  '/undefined',
  '/feature-not-enabled',
  '/future-phase',
] as const;

test.describe('unknown SPA routes', () => {
  for (const [index, path] of unknownPaths.entries()) {
    test(`unknown route ${index + 1} safely redirects to overview`, async ({ page }) => {
      await openAuthenticated(page, path);

      await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Overview', exact: true }))
        .toHaveAttribute('aria-current', 'page');
      await expect(page).toHaveURL('http://127.0.0.1:3001/ui');
      await expect(page.getByText('local-operator', { exact: true })).toBeVisible();
    });
  }
});
