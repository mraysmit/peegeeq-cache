import { expect, test } from '@playwright/test';

import { expectNoBrowserSecrets, openAuthenticated } from '../management-test-support';

const secretCases = Array.from({ length: 30 }, (_, index) => ({
  csrf: `csrf-proof-${index.toString().padStart(2, '0')}-${'s'.repeat(32 + index)}`,
  serverVersion: `version-${index}-csrf-marker-${index.toString().padStart(2, '0')}`,
}));

test.describe('session secret isolation', () => {
  for (const [index, secretCase] of secretCases.entries()) {
    test(`CSRF proof ${index + 1} remains memory-only across shell interactions`, async ({ page }) => {
      const routes = await openAuthenticated(page, index % 2 === 0 ? '/keys' : '/settings', {
        csrfToken: secretCase.csrf,
        serverVersion: secretCase.serverVersion,
      });

      await expectNoBrowserSecrets(page, [secretCase.csrf]);
      await page.getByRole('button', { name: 'Use dark theme' }).click();
      await page.getByRole('button', { name: 'Open notifications' }).click();
      await page.getByRole('link', { name: 'Monitoring', exact: true }).click();
      await expectNoBrowserSecrets(page, [secretCase.csrf]);

      await page.getByRole('button', { name: 'End local session' }).click();
      const deletion = routes.requests.find((request) => request.method() === 'DELETE');
      expect(deletion?.headers()['x-peegeeq-csrf']).toBe(secretCase.csrf);
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
      await expectNoBrowserSecrets(page, [secretCase.csrf]);
    });
  }
});
