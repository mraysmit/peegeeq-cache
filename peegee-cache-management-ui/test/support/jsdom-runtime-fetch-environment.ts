import { builtinEnvironments, type Environment } from 'vitest/environments';

/**
 * The jsdom environment with the runtime's own `AbortController` and `AbortSignal`.
 *
 * vitest's built-in jsdom environment copies jsdom's `AbortController`/`AbortSignal` onto the
 * global while `fetch` stays the Node runtime's. Since undici 7 (bundled from Node 24) `fetch`
 * rejects a `signal` that is not the runtime's `AbortSignal`, so every streaming request the
 * product opens with `new globalThis.AbortController()` would fail before reaching the loopback
 * server. A browser never splits the two, so restoring the runtime pair keeps the test
 * environment faithful to production on every Node version rather than on one pinned release.
 */
const jsdom = builtinEnvironments.jsdom;

const environment: Environment = {
  name: 'jsdom-runtime-fetch',
  transformMode: 'web',
  async setup(global, options) {
    const runtime = { AbortController: global.AbortController, AbortSignal: global.AbortSignal };
    const env = await jsdom.setup(global, options);
    global.AbortController = runtime.AbortController;
    global.AbortSignal = runtime.AbortSignal;
    global.window.AbortController = runtime.AbortController;
    global.window.AbortSignal = runtime.AbortSignal;
    return env;
  },
};

export default environment;
