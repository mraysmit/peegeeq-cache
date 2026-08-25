import { expect, test } from '@playwright/test';

import { managementRoutes, openAuthenticated } from '../management-test-support';

const variants = [
  { name: 'canonical', suffix: '' },
  { name: 'trailing slash', suffix: '/' },
  { name: 'simple query', suffix: '?filter=active' },
  { name: 'encoded separator query', suffix: '?prefix=a%2Fb%25' },
  { name: 'unicode query', suffix: '?q=%E6%9D%B1%E4%BA%AC' },
  { name: 'repeated query', suffix: '?role=viewer&role=operator' },
  { name: 'empty query value', suffix: '?cursor=' },
  { name: 'fragment', suffix: '#workspace' },
  { name: 'encoded fragment', suffix: '#a%2Fb%20c' },
  { name: 'query and fragment', suffix: '?limit=100#workspace' },
] as const;

test.describe('direct and deep management routes', () => {
  for (const route of managementRoutes) {
    for (const variant of variants) {
      test(`${route.label} renders for ${variant.name}`, async ({ page }) => {
        const base = route.path === '/' && variant.suffix.startsWith('/') ? '' : route.path;
        await openAuthenticated(page, `${base}${variant.suffix}` || '/');

        await expect(page.getByRole('heading', { name: route.label, exact: true })).toBeVisible();
        await expect(page.getByRole('link', { name: route.label, exact: true }))
          .toHaveAttribute('aria-current', 'page');
        await expect(page.getByText(
          route.path === '/setups' ? 'Database connections and runtime scope' : 'Authenticated workspace',
          { exact: true },
        )).toBeVisible();
        await expect(page.getByLabel('Session and connection status')).toContainText('Connected');
      });
    }
  }
});
