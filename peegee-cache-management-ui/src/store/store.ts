import { configureStore } from '@reduxjs/toolkit';
import { setupListeners } from '@reduxjs/toolkit/query';

import { managementApi } from './api/managementApi';
import type { ManagementClients } from './clients';
import { registerStoreClients } from './clients-context';

/**
 * Redux store for the management console (reference pattern: `peegeeq-management-ui/src/store`).
 *
 * The typed clients are the thunk `extraArgument`, so every RTK Query endpoint reaches the same
 * session-aware client instance the shell authenticated with. One store is created per
 * authenticated session in `App`; tests create one per case against a loopback server.
 */
export function createManagementStore(clients: ManagementClients) {
  const store = configureStore({
    reducer: {
      [managementApi.reducerPath]: managementApi.reducer,
    },
    middleware: (getDefaultMiddleware) => getDefaultMiddleware({
      thunk: { extraArgument: clients },
    }).concat(managementApi.middleware),
    devTools: false,
  });
  setupListeners(store.dispatch);
  registerStoreClients(store, clients);
  return store;
}

export type ManagementStore = ReturnType<typeof createManagementStore>;
export type RootState = ReturnType<ManagementStore['getState']>;
export type AppDispatch = ManagementStore['dispatch'];
