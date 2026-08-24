import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  base: '/ui/',
  build: {
    emptyOutDir: true,
    outDir: 'target/classes/ui',
    sourcemap: false,
  },
  plugins: [react()],
  resolve: {
    alias: {
      '@generated': fileURLToPath(new URL('./target/generated-sources/openapi', import.meta.url)),
      '@src': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 3001,
    proxy: {
      '/api': 'http://127.0.0.1:8089',
      '/ws': {
        target: 'ws://127.0.0.1:8089',
        ws: true,
      },
    },
  },
});
