import { Card, Typography } from 'antd';
import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { formatDisplayInstant } from '../../presentation/display-time';

import type { SessionTrendPoint } from '../../state/live-store';

/**
 * Current-session trend of live/expired entry counts (reference: the Overview throughput
 * AreaChart in `peegeeq-management-ui/pages/Overview.tsx`). Points are decimal strings from the
 * contract; they are narrowed to numbers only for plotting, never for display.
 */
export function SessionTrendChart({ points }: { readonly points: readonly SessionTrendPoint[] }) {
  const data = points.map((point) => ({
    time: formatDisplayInstant(point.observedAt),
    live: Number(point.liveEntries),
    expired: Number(point.expiredEntries),
  }));
  const latest = points.at(-1);
  return (
    <section aria-label="Current-session cache row trend" className="trend-panel">
      <Card
        extra={<Typography.Text type="secondary">{points.length} {points.length === 1 ? 'snapshot' : 'snapshots'}</Typography.Text>}
        size="small"
        title={(
          <>
            <Typography.Text style={{ display: 'block', fontSize: 12 }} type="secondary">Database-wide · current console session only</Typography.Text>
            <Typography.Title level={2} style={{ margin: 0, fontSize: 16 }}>Live and expired entry trend</Typography.Title>
          </>
        )}
      >
        <div style={{ width: '100%', height: 220 }}>
          <ResponsiveContainer height="100%" width="100%">
            <AreaChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="time" interval="preserveStartEnd" tick={{ fontSize: 12 }} />
              <YAxis allowDecimals={false} tick={{ fontSize: 12 }} />
              <Tooltip />
              <Legend />
              <Area dataKey="live" fill="#1890ff" fillOpacity={0.3} isAnimationActive={false} name="Live entries" stroke="#1890ff" type="monotone" />
              <Area dataKey="expired" fill="#fa8c16" fillOpacity={0.3} isAnimationActive={false} name="Expired entries" stroke="#fa8c16" type="monotone" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
        {latest !== undefined && (
          <Typography.Text className="trend-latest" type="secondary">
            Latest: {BigInt(latest.liveEntries).toLocaleString('en-US')} live, {BigInt(latest.expiredEntries).toLocaleString('en-US')} expired
          </Typography.Text>
        )}
      </Card>
    </section>
  );
}
