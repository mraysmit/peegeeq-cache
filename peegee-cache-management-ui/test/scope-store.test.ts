import { beforeEach, describe, expect, it } from 'vitest';

import { SETUP_SCOPE_STORAGE_KEY } from '@src/state/scope-storage';
import { useSetupScopeStore } from '@src/state/scope-store';

describe('setup and namespace scope transitions', () => {
  beforeEach(() => {
    useSetupScopeStore.getState().clear();
    sessionStorage.clear();
  });

  it('persists a selected namespace only underneath its setup', () => {
    const scope = useSetupScopeStore.getState();
    scope.select('primary-cache');
    useSetupScopeStore.getState().selectNamespace('orders/eu');

    expect(useSetupScopeStore.getState()).toMatchObject({
      setupId: 'primary-cache',
      namespace: 'orders/eu',
    });
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe(
      '{"setupId":"primary-cache","namespace":"orders/eu"}',
    );
  });

  it('retains namespace when the same setup is selected again', () => {
    useSetupScopeStore.getState().select('primary-cache');
    useSetupScopeStore.getState().selectNamespace('orders');

    useSetupScopeStore.getState().select('primary-cache');

    expect(useSetupScopeStore.getState().namespace).toBe('orders');
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe(
      '{"setupId":"primary-cache","namespace":"orders"}',
    );
  });

  it('invalidates namespace when setup changes or is removed', () => {
    useSetupScopeStore.getState().select('primary-cache');
    useSetupScopeStore.getState().selectNamespace('orders');

    useSetupScopeStore.getState().select('analytics-cache');
    expect(useSetupScopeStore.getState()).toMatchObject({
      setupId: 'analytics-cache',
      namespace: undefined,
    });
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe(
      '{"setupId":"analytics-cache"}',
    );

    useSetupScopeStore.getState().selectNamespace('metrics');
    useSetupScopeStore.getState().clear();
    expect(useSetupScopeStore.getState()).toMatchObject({
      setupId: undefined,
      namespace: undefined,
    });
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBeNull();
  });

  it('rejects namespace selection without an active setup', () => {
    expect(() => useSetupScopeStore.getState().selectNamespace('orders'))
      .toThrow('active setup');
  });
});
