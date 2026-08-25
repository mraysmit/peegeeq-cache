import { expect, test } from '@playwright/test';

import { expectNoBrowserSecrets, openAuthenticated } from '../management-test-support';

const identities = [
  'alice',
  'ALICE',
  'alice.smith',
  'alice-smith',
  'alice_smith',
  'alice+ops@example.test',
  'domain\\alice',
  'team/alice',
  'user:alice',
  'user@corp.example',
  '1234567890',
  'a',
  'x'.repeat(256),
  'Åsa',
  'José',
  '東京-operator',
  '客户管理员',
  'مشغل',
  'оператор',
  'δοκιμή',
  'operator 🔒',
  '<script>window.pwned=true</script>',
  '<img src=x onerror=window.pwned=true>',
  '"quoted-user"',
  "user's-name",
  ' leading-space',
  'trailing-space ',
  'two  spaces',
  'line\nbreak',
  'tab\tuser',
  ...Array.from({ length: 10 }, (_, index) => `federated-user-${index.toString().padStart(2, '0')}`),
] as const;

const profiles = [
  {
    name: 'trusted viewer',
    roles: ['viewer'] as Array<'viewer' | 'operator'>,
    authenticationMode: 'TRUSTED_PROXY' as const,
    badge: 'Viewer',
    localLogout: false,
  },
  {
    name: 'local operator',
    roles: ['viewer', 'operator'] as Array<'viewer' | 'operator'>,
    authenticationMode: 'LOCAL_TOKEN' as const,
    badge: 'Operator',
    localLogout: true,
  },
] as const;

test.describe('identity and role rendering matrix', () => {
  for (const [identityIndex, identity] of identities.entries()) {
    for (const profile of profiles) {
      test(`identity ${identityIndex + 1} renders safely as ${profile.name}`, async ({ page }) => {
        await openAuthenticated(page, '/', {
          user: identity,
          roles: [...profile.roles],
          authenticationMode: profile.authenticationMode,
        });

        await expect(page.getByText(identity, { exact: true })).toBeVisible();
        await expect(page.getByText(profile.badge, { exact: true })).toBeVisible();
        await expect(page.getByRole('button', { name: 'End local session' }))
          .toHaveCount(profile.localLogout ? 1 : 0);
        expect(await page.evaluate(() => Reflect.get(window, 'pwned'))).toBeUndefined();
        await expectNoBrowserSecrets(page, ['c'.repeat(43)]);
      });
    }
  }
});
