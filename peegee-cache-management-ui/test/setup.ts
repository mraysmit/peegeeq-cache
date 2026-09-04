import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(cleanup);

// Browser API polyfills that jsdom does not implement. These are environment shims for real
// browser APIs (the reference console's vitest.setup does the same), not test doubles of product
// code: Ant Design's responsive layout and observers call them during render.
if (typeof window !== 'undefined') {
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        dispatchEvent: () => false,
      }),
    });
  }
  if (typeof globalThis.ResizeObserver !== 'function') {
    globalThis.ResizeObserver = class ResizeObserver {
      observe() { /* jsdom has no layout */ }
      unobserve() { /* jsdom has no layout */ }
      disconnect() { /* jsdom has no layout */ }
    };
  }
  if (typeof window.getComputedStyle === 'function' && typeof (window as unknown as { scrollTo?: unknown }).scrollTo !== 'function') {
    window.scrollTo = () => undefined;
  }
}
