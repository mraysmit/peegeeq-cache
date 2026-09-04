import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@generated': fileURLToPath(new URL('./target/generated-sources/openapi', import.meta.url)),
      '@src': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    coverage: {
      // U11 gate: branch coverage on the contract and state layers. Pages are covered by the
      // Java Playwright catalogue in peegee-cache-rest and are not thresholded here.
      exclude: ['test/**', 'target/**', 'node/**', 'src/main.tsx', 'src/vite-env.d.ts', '**/*.config.*'],
      include: ['src/**/*.{ts,tsx}'],
      provider: 'v8',
      reportOnFailure: true,
      reporter: ['text-summary', 'json-summary', 'html'],
      reportsDirectory: 'target/coverage',
      thresholds: {
        'src/api/**': { branches: 80 },
        'src/state/**': { branches: 80 },
      },
    },
    environment: 'jsdom',
    include: ['test/**/*.test.{ts,tsx}'],
    setupFiles: ['./test/setup.ts'],
    testTimeout: 15_000,
  },
});
