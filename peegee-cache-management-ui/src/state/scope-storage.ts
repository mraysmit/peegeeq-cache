import { encodeNamespace } from '../api/identifier-codec';

export const SETUP_SCOPE_STORAGE_KEY = 'peegeeq-cache.scope.v1';

const setupIdPattern = /^[a-z][a-z0-9-]{0,62}$/u;

export interface PersistedSetupScope {
  readonly setupId: string;
  readonly namespace?: string;
}

export function readSetupScope(): PersistedSetupScope | undefined {
  const storage = browserSessionStorage();
  if (storage === undefined) return undefined;
  const raw = storage.getItem(SETUP_SCOPE_STORAGE_KEY);
  if (raw === null) return undefined;
  try {
    const value = JSON.parse(raw) as unknown;
    if (!isAllowlistedScope(value)) throw new Error('Persisted setup scope is invalid');
    return value;
  } catch {
    storage.removeItem(SETUP_SCOPE_STORAGE_KEY);
    return undefined;
  }
}

export function readSetupScopeId(): string | undefined {
  return readSetupScope()?.setupId;
}

export function writeSetupScopeId(setupId: string): void {
  writeSetupScope({ setupId });
}

export function writeSetupScope(scope: PersistedSetupScope): void {
  const { setupId, namespace } = scope;
  if (!setupIdPattern.test(setupId)) {
    throw new Error('Setup identifier must be canonical');
  }
  if (namespace !== undefined) encodeNamespace(namespace);
  const persisted: PersistedSetupScope = namespace === undefined
    ? { setupId }
    : { setupId, namespace };
  browserSessionStorage()?.setItem(SETUP_SCOPE_STORAGE_KEY, JSON.stringify(persisted));
}

export function clearSetupScopeStorage(): void {
  browserSessionStorage()?.removeItem(SETUP_SCOPE_STORAGE_KEY);
}

function isAllowlistedScope(value: unknown): value is PersistedSetupScope {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const entries = Object.entries(value);
  if ((entries.length !== 1 && entries.length !== 2)
      || entries[0]?.[0] !== 'setupId'
      || typeof entries[0][1] !== 'string'
      || !setupIdPattern.test(entries[0][1])) {
    return false;
  }
  if (entries.length === 1) return true;
  if (entries[1]?.[0] !== 'namespace' || typeof entries[1][1] !== 'string') return false;
  try {
    encodeNamespace(entries[1][1]);
    return true;
  } catch {
    return false;
  }
}

function browserSessionStorage(): Storage | undefined {
  return typeof window === 'undefined' ? undefined : window.sessionStorage;
}
