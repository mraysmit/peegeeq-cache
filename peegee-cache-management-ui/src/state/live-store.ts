import { create } from 'zustand';

import type { Overview } from '../api/inspection-schemas';
import type { MonitoringConnectionState, MonitoringEnvelope } from '../api/monitoring-live';
import type { PubSubMessageMetadata } from '../api/pubsub-schemas';

export type PubSubConnectionState = 'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED';

export interface SessionTrendPoint {
  readonly expiredEntries: string;
  readonly liveEntries: string;
  readonly observedAt: string;
}

/**
 * Live-connection and notification-drawer state (design §8.2: owned by Zustand, never Redux).
 *
 * Holds only event envelopes — type, id, time, setup, and non-sensitive metadata. Payloads,
 * revealed values, and owners never pass through here; the monitoring envelope schema rejects
 * them at the transport.
 */
interface LiveState {
  readonly connectionState: MonitoringConnectionState;
  readonly notificationsOpen: boolean;
  readonly notifications: readonly MonitoringEnvelope[];
  readonly setConnectionState: (state: MonitoringConnectionState) => void;
  readonly openNotifications: () => void;
  readonly closeNotifications: () => void;
  readonly toggleNotifications: () => void;
  readonly receive: (event: MonitoringEnvelope) => void;
  readonly clearNotifications: () => void;
  readonly reset: () => void;
  /** Current-session Overview trend per setup (reference: `updateChartData` in the management store). */
  readonly trend: Readonly<Record<string, readonly SessionTrendPoint[]>>;
  readonly recordSnapshot: (setupId: string, snapshot: Overview) => void;
  /** Live Pub/Sub subscription state: connection and the bounded, metadata-only message buffer. */
  readonly pubSubConnection: PubSubConnectionState;
  readonly pubSubMessages: readonly PubSubMessageMetadata[];
  readonly setPubSubConnection: (state: PubSubConnectionState) => void;
  readonly receivePubSubMessage: (message: PubSubMessageMetadata, bufferLimit: number) => void;
  readonly clearPubSubMessages: () => void;
  readonly stopPubSub: () => void;
}

const RETAINED_NOTIFICATIONS = 100;
const RETAINED_TREND_POINTS = 30;

export const useLiveStore = create<LiveState>((set) => ({
  connectionState: 'STOPPED',
  notificationsOpen: false,
  notifications: [],
  setConnectionState: (connectionState) => set({ connectionState }),
  openNotifications: () => set({ notificationsOpen: true }),
  closeNotifications: () => set({ notificationsOpen: false }),
  toggleNotifications: () => set((current) => ({ notificationsOpen: !current.notificationsOpen })),
  receive: (event) => set((current) => ({
    notifications: [event, ...current.notifications.filter((item) => item.eventId !== event.eventId)].slice(0, RETAINED_NOTIFICATIONS),
  })),
  clearNotifications: () => set({ notifications: [] }),
  reset: () => set({ notifications: [], connectionState: 'STOPPED' }),
  trend: {},
  recordSnapshot: (setupId, snapshot) => set((current) => {
    const point: SessionTrendPoint = {
      observedAt: snapshot.observedAt,
      liveEntries: snapshot.totals.liveEntryCount,
      expiredEntries: snapshot.totals.expiredEntryCount,
    };
    const existing = current.trend[setupId] ?? [];
    if (existing.some((candidate) => candidate.observedAt === point.observedAt)) return {};
    return { trend: { ...current.trend, [setupId]: [...existing, point].slice(-RETAINED_TREND_POINTS) } };
  }),
  pubSubConnection: 'STOPPED',
  pubSubMessages: [],
  setPubSubConnection: (pubSubConnection) => set({ pubSubConnection }),
  receivePubSubMessage: (message, bufferLimit) => set((current) => ({
    pubSubMessages: [message, ...current.pubSubMessages.filter((item) => item.messageId !== message.messageId)].slice(0, bufferLimit),
  })),
  clearPubSubMessages: () => set({ pubSubMessages: [] }),
  stopPubSub: () => set({ pubSubMessages: [], pubSubConnection: 'STOPPED' }),
}));
