import { create } from 'zustand';

import type { MonitoringConnectionState, MonitoringEnvelope } from '../api/monitoring-live';

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
}

const RETAINED_NOTIFICATIONS = 100;

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
}));
