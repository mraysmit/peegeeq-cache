export type SecurityProfile =
  | 'SESSION'
  | 'LOCAL_BOOTSTRAP'
  | 'VIEW_MUTATE'
  | 'VIEW'
  | 'OPERATE'
  | 'REVEAL'
  | 'SSE'
  | 'WS';

export type OperationTransport = 'rest' | 'sse' | 'websocket';

export type FeatureSlice =
  | 'session'
  | 'setups'
  | 'overview'
  | 'namespaces'
  | 'entries'
  | 'counters'
  | 'locks'
  | 'pubsub'
  | 'monitoring'
  | 'activity';

export interface OperationClassification {
  readonly operationId: string;
  readonly feature: FeatureSlice;
  readonly securityProfile: SecurityProfile;
  readonly transport: OperationTransport;
  readonly csrfProtected: boolean;
  readonly sensitiveResponse: boolean;
}

function classify(
  feature: FeatureSlice,
  securityProfile: SecurityProfile,
  operationIds: readonly string[],
  transport: OperationTransport = 'rest',
): OperationClassification[] {
  return operationIds.map((operationId) => ({
    operationId,
    feature,
    securityProfile,
    transport,
    csrfProtected: securityProfile === 'OPERATE'
      || securityProfile === 'REVEAL'
      || securityProfile === 'VIEW_MUTATE',
    sensitiveResponse: securityProfile === 'REVEAL' || operationId === 'getSession' || operationId === 'exchangeLocalToken',
  }));
}

export const operationManifest: readonly OperationClassification[] = [
  ...classify('session', 'SESSION', ['getSession']),
  ...classify('session', 'LOCAL_BOOTSTRAP', ['exchangeLocalToken']),
  ...classify('session', 'VIEW_MUTATE', ['deleteLocalSession']),
  ...classify('setups', 'VIEW', ['listSetups', 'getSetup', 'getSetupHealth', 'getSetupCapabilities']),
  ...classify('setups', 'OPERATE', [
    'registerSetup',
    'testUnregisteredSetup',
    'forgetSetup',
    'connectSetup',
    'testRegisteredSetup',
    'detachSetup',
  ]),
  ...classify('overview', 'VIEW', ['getOverview']),
  ...classify('namespaces', 'VIEW', ['listNamespaces', 'exportNamespaces', 'getNamespace']),
  ...classify('entries', 'VIEW', ['listEntries', 'getEntry']),
  ...classify('entries', 'OPERATE', [
    'setEntry',
    'deleteEntry',
    'expireEntry',
    'persistEntry',
    'touchEntry',
    'previewEntryBulkDelete',
    'executeEntryBulkDelete',
  ]),
  ...classify('entries', 'REVEAL', ['revealEntryValue']),
  ...classify('counters', 'VIEW', ['listCounters', 'getCounter']),
  ...classify('counters', 'OPERATE', [
    'setCounter',
    'deleteCounter',
    'adjustCounter',
    'expireCounter',
    'persistCounter',
    'previewCounterBulkDelete',
    'executeCounterBulkDelete',
  ]),
  ...classify('locks', 'VIEW', ['listLocks', 'getLock']),
  ...classify('locks', 'REVEAL', ['revealLockOwner']),
  ...classify('locks', 'OPERATE', ['forceReleaseLock']),
  ...classify('pubsub', 'VIEW_MUTATE', ['createPubSubSubscription', 'deletePubSubSubscription']),
  ...classify('pubsub', 'SSE', ['streamPubSubMessages'], 'sse'),
  ...classify('pubsub', 'REVEAL', ['revealPubSubPayload']),
  ...classify('pubsub', 'OPERATE', ['publishPubSubMessage']),
  ...classify('monitoring', 'VIEW', ['getDatabaseMonitoring', 'getRuntimeMonitoring']),
  ...classify('monitoring', 'SSE', ['streamMetrics'], 'sse'),
  ...classify('activity', 'VIEW', ['listActivity']),
  ...classify('activity', 'WS', ['monitoringWebSocket'], 'websocket'),
];
