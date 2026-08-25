import { expect, test } from '@playwright/test';

import { expectNoBrowserSecrets, openLogin, problem } from '../management-test-support';

const tokens = [
  'unique-a-token-case-001',
  'unique-bootstrap-token-case-002',
  'token-with-dashes',
  'token_with_underscores',
  'token.with.dots',
  'token/with/slashes',
  'token+with+plus',
  'token=with=padding',
  'token with spaces',
  ' leading-token',
  'trailing-token ',
  'two  spaces',
  'quoted-"token"',
  "single-'token'",
  '<script>alert(1)</script>',
  '<img src=x onerror=alert(1)>',
  '東京-bootstrap',
  '客户令牌',
  'رمز-التمهيد',
  '🔐-bootstrap-token',
  'unique-zero-token-case-021',
  '9'.repeat(32),
  'a'.repeat(64),
  'b'.repeat(128),
  'c'.repeat(256),
  ...Array.from({ length: 25 }, (_, index) => `one-time-token-${index.toString().padStart(2, '0')}`),
] as const;

test.describe('local bootstrap token exchange matrix', () => {
  for (const [index, token] of tokens.entries()) {
    test(`token case ${index + 1} is sent once, cleared, and never rendered`, async ({ page }) => {
      const routes = await openLogin(page, {
        status: 401,
        body: problem(401, 'INVALID_BOOTSTRAP_TOKEN', `Rejected token case ${index + 1}`, `login-${index + 1}`),
      });
      const input = page.getByLabel('Bootstrap token');
      await expect(input).toHaveAttribute('type', 'password');
      await expect(input).toHaveAttribute('autocomplete', 'off');
      await input.fill(token);
      await page.getByRole('button', { name: 'Connect' }).click();

      await expect(page.getByRole('alert')).toContainText('INVALID_BOOTSTRAP_TOKEN');
      await expect(page.getByRole('alert')).toContainText(`login-${index + 1}`);
      await expect(input).toHaveValue('');
      const post = routes.requests.find((request) => request.method() === 'POST');
      expect(post).toBeDefined();
      expect(post?.postDataJSON()).toEqual({ token });
      expect(post?.headers()['content-type']).toContain('application/json');
      await expectNoBrowserSecrets(page, [token]);
    });
  }
});
