import { expect, test } from '@playwright/test';

import { installSessionRoutes, problem } from '../management-test-support';

const statuses = [
  400, 402, 403, 404, 405, 406, 407, 408, 409, 410,
  411, 412, 413, 414, 415, 416, 417, 418, 421, 422,
  423, 424, 425, 426, 428, 429, 431, 451, 500, 501,
  502, 503, 504, 505, 506, 507, 508, 510, 511, 599,
] as const;

test.describe('management problem response matrix', () => {
  for (const status of statuses) {
    test(`HTTP ${status} renders bounded diagnostics and supports retry`, async ({ page }) => {
      const code = `SESSION_FAILURE_${status}`;
      const detail = status % 2 === 0
        ? `Session request failed with status ${status}`
        : `Failure ${status}: <script>window.pwned=true</script>`;
      const routes = await installSessionRoutes(page, {
        get: { status, body: problem(status, code, detail, `problem-${status}`) },
      });
      await page.goto('/ui/');

      await expect(page.getByRole('heading', { name: 'Management server unavailable' })).toBeVisible();
      await expect(page.getByRole('alert')).toContainText(code);
      await expect(page.getByRole('alert')).toContainText(detail);
      await expect(page.getByRole('alert')).toContainText(`problem-${status}`);
      expect(await page.evaluate(() => Reflect.get(window, 'pwned'))).toBeUndefined();

      routes.get = { status: 401, body: problem(401, 'AUTHENTICATION_REQUIRED') };
      await page.getByRole('button', { name: 'Retry connection' }).click();
      await expect(page.getByRole('heading', { name: 'Connect to management console' })).toBeVisible();
    });
  }
});
