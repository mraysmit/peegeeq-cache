import type { ReactNode } from 'react';
import { Provider } from 'react-redux';

import { ManagementClientsContext, clientsOfStore } from './clients-context';
import type { ManagementStore } from './store';

/**
 * Provides the Redux store and the typed clients. Pages use RTK Query hooks for cached reads and
 * committed mutations, and `useManagementClients()` only for the sensitive no-store reveal paths.
 */
export function ManagementProvider({ store, children }: { readonly store: ManagementStore; readonly children: ReactNode }) {
  return (
    <Provider store={store}>
      <ManagementClientsContext.Provider value={clientsOfStore(store)}>{children}</ManagementClientsContext.Provider>
    </Provider>
  );
}
