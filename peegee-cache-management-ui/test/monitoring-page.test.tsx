import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { MetricsStreamHandlers, MetricsStreamPort } from '@src/api/live-transport';
import type { MonitoringClientPort } from '@src/api/inspection-client';
import type { ActivityPage, DatabaseMonitoring, RuntimeMonitoring } from '@src/api/inspection-schemas';
import { MonitoringPage } from '@src/features/monitoring/MonitoringPage';

const health = { status: 'UP' as const, schemaReady: true, latencyMillis: 2, checkedAt: '2026-08-29T10:00:00Z', detail: 'Ready' };
const database: DatabaseMonitoring = { scope: 'DATABASE', observedAt: '2026-08-29T10:00:00Z', health, tableBytes: available('1'), indexBytes: available('1'), schemaBytes: available('2'), liveRows: available('3'), expiredRows: available('0'), deadTuples: available('0'), lastVacuumAt: null, lastAutovacuumAt: null, databaseConnections: available('1'), cacheConnections: available('1'), expiryBacklog: '0', oldestExpiredRowLagMillis: null };
const runtime: RuntimeMonitoring = { scope: 'MANAGEMENT_RUNTIME', observedAt: '2026-08-29T10:00:00Z', lifecycleState: 'RUNNING', pool: { active: available('1'), idle: available('2'), pending: available('0'), maximum: available('3') }, activeOperations: '1', pubSubSubscriptions: '0', sseClients: '1', webSocketClients: '1', retainedPayloadBytes: '0', auditQueue: { depth: '0', capacity: '100', acceptingMutations: true }, expirySweeper: { ownedByRuntime: true, running: true, lastSweepAt: null }, operations: [] };
const activity: ActivityPage = { items: [], nextAfter: null, hasMore: false };

class Client implements MonitoringClientPort {
  async databaseMonitoring(): Promise<DatabaseMonitoring> { return database; }
  async runtimeMonitoring(): Promise<RuntimeMonitoring> { return runtime; }
  async activity(): Promise<ActivityPage> { return activity; }
}

class Metrics implements MetricsStreamPort {
  path?: string;
  handlers?: MetricsStreamHandlers;
  stopped = false;
  connect(path: string, handlers: MetricsStreamHandlers) {
    this.path = path;
    this.handlers = handlers;
    handlers.onState?.('CONNECTED');
    return { stop: () => { this.stopped = true; } };
  }
}

describe('U8 live monitoring page', () => {
  it('connects the setup-scoped metrics stream, presents state, and stops it on disposal', async () => {
    const metrics = new Metrics();
    const rendered = render(<MonitoringPage client={new Client()} metrics={metrics} selectedSetupId="primary" />);

    expect(await screen.findByText('Live metrics connected')).toBeVisible();
    expect(metrics.path).toBe('/api/v1/setups/primary/sse/metrics');

    rendered.unmount();
    expect(metrics.stopped).toBe(true);
  });
});

function available(value: string) { return { availability: 'AVAILABLE' as const, reason: null, value }; }
