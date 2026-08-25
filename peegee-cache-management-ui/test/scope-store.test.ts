import { beforeEach, describe, expect, it } from 'vitest';

import { SETUP_SCOPE_STORAGE_KEY } from '@src/state/scope-storage';
import { useSetupScopeStore } from '@src/state/scope-store';
import type { SetupCapabilities } from '@src/api/setup-schemas';

const capabilities: SetupCapabilities = {
  migrationVersion: '1',
  capabilities: {
    namespaceInspection: true,
    expiredEntryInspection: true,
    counterInspection: true,
    lockInspection: true,
    forcedLockRelease: true,
    bulkEntryDelete: true,
    bulkCounterDelete: true,
    pubSub: true,
    databaseStatistics: true,
    sensitiveValueReveal: true,
  },
  limits: {
    pubSubChannelMaxBytes: 63,
    pubSubPayloadMaxBytes: 7_500,
    maximumValueBytes: 1_048_576,
  },
};

describe('setup and namespace scope transitions', () => {
  beforeEach(() => {
    useSetupScopeStore.getState().clear();
    sessionStorage.clear();
  });

  it('persists a selected namespace only underneath its setup', () => {
    const scope = useSetupScopeStore.getState();
    scope.select('primary-cache', capabilities);
    useSetupScopeStore.getState().selectNamespace('orders/eu');

    expect(useSetupScopeStore.getState()).toMatchObject({
      setupId: 'primary-cache',
      namespace: 'orders/eu',
    });
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe(
      '{"setupId":"primary-cache","namespace":"orders/eu"}',
    );
  });

  it('retains namespace during same-setup capability revalidation', () => {
    useSetupScopeStore.getState().select('primary-cache', capabilities);
    useSetupScopeStore.getState().selectNamespace('orders');

    useSetupScopeStore.getState().select('primary-cache', {
      ...capabilities,
      migrationVersion: '2',
    });

    expect(useSetupScopeStore.getState().namespace).toBe('orders');
    expect(useSetupScopeStore.getState().capabilities?.migrationVersion).toBe('2');
  });

  it('invalidates namespace when setup changes or is removed', () => {
    useSetupScopeStore.getState().select('primary-cache', capabilities);
    useSetupScopeStore.getState().selectNamespace('orders');

    useSetupScopeStore.getState().select('analytics-cache', capabilities);
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
