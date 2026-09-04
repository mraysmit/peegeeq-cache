import { createContext, useContext } from 'react';

import type { ManagementClients } from './clients';

const ClientsContext = createContext<ManagementClients | undefined>(undefined);

export const ManagementClientsContext = ClientsContext;

/**
 * The typed clients for the sensitive no-store reveal paths that must never enter Redux state.
 * Everything else goes through the RTK Query hooks.
 */
export function useManagementClients(): ManagementClients {
  const clients = useContext(ClientsContext);
  if (clients === undefined) throw new Error('useManagementClients must be used within ManagementProvider');
  return clients;
}

const clientsByStore = new WeakMap<object, ManagementClients>();

export function registerStoreClients(store: object, clients: ManagementClients): void {
  clientsByStore.set(store, clients);
}

export function clientsOfStore(store: object): ManagementClients {
  const known = clientsByStore.get(store);
  if (known === undefined) throw new Error('ManagementProvider requires a store created by createManagementStore');
  return known;
}
