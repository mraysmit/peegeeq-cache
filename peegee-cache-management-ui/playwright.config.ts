import { defineConfig, devices } from '@playwright/test';

const headed = process.env.PLAYWRIGHT_HEADED === 'true';
const slowMotion = Number.parseInt(process.env.PLAYWRIGHT_SLOW_MO ?? '0', 10);

export default defineConfig({
  testDir: './test/e2e/specs',
  outputDir: 'target/playwright-results',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : 4,
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'target/playwright-report' }],
    ['json', { outputFile: 'target/playwright-results/results.json' }],
    ['junit', { outputFile: 'target/playwright-results/junit.xml' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:3001',
    channel: 'chrome',
    headless: !headed,
    launchOptions: {
      slowMo: Number.isFinite(slowMotion) && slowMotion >= 0 ? slowMotion : 0,
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: false,
  },
  projects: [
    {
      name: 'desktop-chrome',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:3001/ui/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
