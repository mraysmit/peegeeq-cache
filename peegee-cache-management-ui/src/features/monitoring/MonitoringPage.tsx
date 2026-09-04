import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useDispatch } from 'react-redux';

import { SetupScopeBar } from '../../components/common/SetupScopeBar';
import { isManagementQueryError, type ManagementQueryError } from '../../store/api/apiBase';
import { inspectionApi, useGetActivityQuery, useGetDatabaseMonitoringQuery, useGetRuntimeMonitoringQuery } from '../../store/api/inspectionApi';
import { useManagementClients, type AppDispatch } from '../../store';
import { OverviewMonitoring } from '../overview/OverviewMonitoring';

const { Title, Text } = Typography;

type LiveState = 'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED';

/**
 * Monitoring: the shared monitoring panels driven by RTK Query, refreshed by the setup-scoped
 * metrics SSE stream (`MetricsSseTransport` from the client bundle). SSE runtime snapshots are
 * pushed straight into the RTK Query cache; overview/reset events trigger a refetch.
 */
export function MonitoringPage({ selectedSetupId }: { readonly selectedSetupId?: string }) {
  const clients = useManagementClients();
  const dispatch = useDispatch<AppDispatch>();
  const setupId = selectedSetupId ?? '';
  const skip = selectedSetupId === undefined;
  const database = useGetDatabaseMonitoringQuery({ setupId }, { skip });
  const runtime = useGetRuntimeMonitoringQuery({ setupId }, { skip });
  const activity = useGetActivityQuery({ setupId, query: { limit: 100 } }, { skip });
  const [liveState, setLiveState] = useState<LiveState>('STOPPED');
  const [liveMessage, setLiveMessage] = useState('');
  const refetchDatabase = database.refetch;
  const refetchRuntime = runtime.refetch;
  const refetchActivity = activity.refetch;

  const refresh = () => {
    void refetchDatabase();
    void refetchRuntime();
    void refetchActivity();
  };

  useEffect(() => {
    if (selectedSetupId === undefined) return undefined;
    const stream = clients.metricsStream.connect(`${clients.origin}/api/v1/setups/${encodeURIComponent(selectedSetupId)}/sse/metrics`, {
      // Live snapshots are already Zod-validated by the transport; they replace the cached query
      // data so every subscriber (this page, Overview) sees them without a second request.
      onOverview: (snapshot) => {
        dispatch(inspectionApi.util.upsertQueryData('getOverview', { setupId: selectedSetupId }, snapshot));
        void refetchDatabase();
        void refetchActivity();
      },
      onRuntime: (snapshot) => {
        dispatch(inspectionApi.util.upsertQueryData('getRuntimeMonitoring', { setupId: selectedSetupId }, snapshot));
      },
      onState: setLiveState,
      onReset: (reason) => { setLiveMessage(reason); void refetchDatabase(); void refetchRuntime(); void refetchActivity(); },
      onError: setLiveMessage,
    });
    return () => stream.stop();
  }, [clients, dispatch, refetchActivity, refetchDatabase, refetchRuntime, selectedSetupId]);

  const busy = database.isFetching || runtime.isFetching || activity.isFetching;

  if (selectedSetupId === undefined) {
    return (
      <section aria-labelledby="monitoring-title" className="workspace">
        <Text className="workspace__context" type="secondary">Database-wide and management-server-local telemetry</Text>
        <Title id="monitoring-title" level={1} style={{ marginTop: 4 }}>Monitoring</Title>
        <SetupScopeBar />
        <Card><Empty description="Select a connected setup before monitoring it." /></Card>
      </section>
    );
  }

  return (
    <section aria-labelledby="monitoring-title" className="workspace">
      <Text className="workspace__context" type="secondary">Database-wide and management-server-local telemetry</Text>
      <div className="section-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 16 }}>
        <div>
          <Title id="monitoring-title" level={1} style={{ marginTop: 4 }}>Monitoring</Title>
          <Text aria-live="polite" className="live-status" type="secondary">{liveLabel(liveState)}</Text>
        </div>
        <Button disabled={busy} icon={<ReloadOutlined aria-hidden="true" />} onClick={refresh}>{busy ? 'Refreshing…' : 'Refresh monitoring'}</Button>
      </div>
      <SetupScopeBar />
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {liveMessage !== '' && <Alert description={liveMessage} message="Live transport notice" role="status" showIcon type="warning" />}
        <OverviewMonitoring
          activity={activity.data}
          activityProblem={queryError(activity.error)}
          database={database.data}
          databaseProblem={queryError(database.error)}
          runtime={runtime.data}
          runtimeProblem={queryError(runtime.error)}
        />
      </Space>
    </section>
  );
}

function queryError(error: unknown): ManagementQueryError | undefined {
  return isManagementQueryError(error) ? error : undefined;
}

function liveLabel(state: LiveState): string {
  switch (state) {
    case 'CONNECTED': return 'Live metrics connected';
    case 'CONNECTING': return 'Connecting live metrics…';
    case 'STALE': return 'Live metrics interrupted; displayed values may be stale';
    case 'STOPPED': return 'Live metrics stopped';
  }
}
