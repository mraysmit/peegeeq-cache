import { create } from 'zustand';

import type { SetupCapabilities } from '../api/setup-schemas';
import {
  clearSetupScopeStorage,
  readSetupScope,
  writeSetupScope,
} from './scope-storage';

interface SetupScopeState {
  readonly setupId?: string;
  readonly namespace?: string;
  readonly capabilities?: SetupCapabilities;
  readonly select: (setupId: string, capabilities: SetupCapabilities) => void;
  readonly selectNamespace: (namespace?: string) => void;
  readonly clear: () => void;
}

const initialScope = readSetupScope();

export const useSetupScopeStore = create<SetupScopeState>((set) => ({
  setupId: initialScope?.setupId,
  namespace: initialScope?.namespace,
  capabilities: undefined,
  select: (setupId, capabilities) => set((current) => {
    const namespace = current.setupId === setupId ? current.namespace : undefined;
    writeSetupScope({ setupId, namespace });
    return { setupId, namespace, capabilities };
  }),
  selectNamespace: (namespace) => set((current) => {
    if (current.setupId === undefined) {
      throw new Error('Cannot select a namespace without an active setup');
    }
    writeSetupScope({ setupId: current.setupId, namespace });
    return { namespace };
  }),
  clear: () => {
    clearSetupScopeStorage();
    set({ setupId: undefined, namespace: undefined, capabilities: undefined });
  },
}));

export function clearSetupScope(): void {
  useSetupScopeStore.getState().clear();
}
