import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated, openLogin, problem } from '../management-test-support';

test.describe('landmarks and accessible names', () => {
  for (const route of managementRoutes) {
    test(`${route.label} exposes the shell landmarks and one page heading`, async ({ page }) => {
      await openAuthenticated(page, route.path);

      await expect(page.getByRole('banner')).toHaveCount(1);
      await expect(page.getByRole('navigation', { name: 'Management sections' })).toHaveCount(1);
      await expect(page.getByRole('main')).toHaveCount(1);
      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
      await expect(page.getByLabel('Session and connection status')).toContainText('Connected');
    });
  }
});

test.describe('keyboard route activation', () => {
  for (const route of managementRoutes) {
    test(`${route.label} navigation link activates with Enter`, async ({ page }) => {
      await openAuthenticated(page, route.label === 'Overview' ? '/settings' : '/');
      const link = page.getByRole('link', { name: route.label, exact: true });
      await link.focus();
      await expect(link).toBeFocused();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('heading', { name: route.label, exact: true })).toBeVisible();
      await expect(link).toHaveAttribute('aria-current', 'page');
    });
  }
});

test('bootstrap form submits from the keyboard and returns focusable diagnostics', async ({ page }) => {
  await openLogin(page, {
    status: 401,
    body: problem(401, 'INVALID_BOOTSTRAP_TOKEN', 'Keyboard token rejected', 'keyboard-login'),
  });
  const input = page.getByLabel('Bootstrap token');
  await input.fill('keyboard-token');
  await input.press('Enter');
  await expect(page.getByRole('alert')).toContainText('Keyboard token rejected');
  await expect(input).toHaveValue('');
});

test('notification controls publish their expanded state to assistive technology', async ({ page }) => {
  await openAuthenticated(page);
  const toggle = page.getByRole('button', { name: 'Open notifications' });
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Close notifications' }).first())
    .toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByRole('complementary', { name: 'Notifications' })).toBeVisible();
});
