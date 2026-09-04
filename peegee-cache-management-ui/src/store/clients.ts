import { BackendCapabilityClient } from '../api/backend-capability-client';
import { EntryAdministrationClient } from '../api/entry-administration-client';
import { InspectionClient } from '../api/inspection-client';
import { PubSubClient } from '../api/pubsub-client';
import { ResourceClient } from '../api/resource-client';
import { SessionClient } from '../api/session-client';
import { SetupClient } from '../api/setup-client';

/**
 * The typed `src/api` clients that the RTK Query endpoints delegate to.
 *
 * All clients share one `SessionClient`, so CSRF, credentials, Origin, no-store handling, and
 * problem mapping exist in exactly one place. RTK Query owns request state, caching, and
 * invalidation on top of them (design §3.1/§8.2). Sensitive reveal operations are reached through
 * `useManagementClients()` and never through an RTK Query endpoint.
 */
export interface ManagementClients {
  readonly session: SessionClient;
  readonly setup: SetupClient;
  readonly inspection: InspectionClient;
  readonly entryAdministration: EntryAdministrationClient;
  readonly resource: ResourceClient;
  readonly pubSub: PubSubClient;
  readonly backendCapability: BackendCapabilityClient;
}

export function createManagementClients(session: SessionClient): ManagementClients {
  return {
    session,
    setup: new SetupClient(session),
    inspection: new InspectionClient(session),
    entryAdministration: new EntryAdministrationClient(session),
    resource: new ResourceClient(session),
    pubSub: new PubSubClient(session),
    backendCapability: new BackendCapabilityClient(session),
  };
}
