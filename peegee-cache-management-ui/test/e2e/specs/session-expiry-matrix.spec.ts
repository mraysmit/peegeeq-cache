import { expect, test } from '@playwright/test';

import { openAuthenticated } from '../management-test-support';

const expiries = Array.from({ length: 20 }, (_, index) => ({
  duration: 1_000 + index * 50,
  limitingClock: index % 2 === 0 ? 'idle' : 'absolute',
}));

test.describe('browser-side session expiry matrix', () => {
  for (const [index, expiry] of expiries.entries()) {
    test(`expiry case ${index + 1} honors the ${expiry.limitingClock} deadline`, async ({ page }) => {
      const baseTime = Date.UTC(2030, 0, 1, 0, 0, 0);
      const checkpoint = baseTime + 60_000;
      await page.clock.install({ time: baseTime });
      const limiting = new Date(checkpoint + expiry.duration).toISOString();
      const distant = new Date(checkpoint + 60_000).toISOString();
      await openAuthenticated(page, index % 2 === 0 ? '/' : '/monitoring', {
        sessionIdleExpiresAt: expiry.limitingClock === 'idle' ? limiting : distant,
        sessionExpiresAt: expiry.limitingClock === 'absolute' ? limiting : distant,
      });

      await page.clock.pauseAt(checkpoint);
      await page.clock.runFor(expiry.duration + 1);
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
      await expect(page.locator('.console')).toHaveCount(0);
      expect(await page.evaluate(() => localStorage.length)).toBe(0);
      expect(await page.evaluate(() => sessionStorage.length)).toBe(0);
    });
  }
});
