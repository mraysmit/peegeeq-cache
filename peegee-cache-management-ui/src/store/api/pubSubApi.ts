import type { PublishAccepted, SubscriptionSummary } from '../../api/pubsub-schemas';
import { clientsOf, delegate } from './apiBase';
import { managementApi } from './managementApi';

/**
 * Pub/Sub control-plane mutations. Message delivery is a live SSE transport owned by the Zustand
 * connection store, not a cached query, and payload reveal is sensitive and stays on
 * `PubSubClient.revealPayload` via `useManagementClients()`.
 */
export const pubSubApi = managementApi.injectEndpoints({
  endpoints: (build) => ({
    createSubscription: build.mutation<SubscriptionSummary, { setupId: string; channel: string; bufferLimit: number }>({
      queryFn: ({ setupId, channel, bufferLimit }, api) => delegate(() => clientsOf(api).pubSub.createSubscription(setupId, channel, bufferLimit)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Subscription', id: setupId }, { type: 'Monitoring', id: setupId }],
    }),
    publishMessage: build.mutation<PublishAccepted, { setupId: string; channel: string; payload: string; contentType?: string }>({
      queryFn: ({ setupId, channel, payload, contentType }, api) => delegate(() => clientsOf(api).pubSub.publish(setupId, channel, payload, contentType)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Activity', id: setupId }],
    }),
    deleteSubscription: build.mutation<void, { setupId: string; subscriptionId: string }>({
      queryFn: ({ setupId, subscriptionId }, api) => delegate(() => clientsOf(api).pubSub.deleteSubscription(setupId, subscriptionId)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Subscription', id: setupId }, { type: 'Monitoring', id: setupId }],
    }),
  }),
});

export const {
  useCreateSubscriptionMutation,
  usePublishMessageMutation,
  useDeleteSubscriptionMutation,
} = pubSubApi;
