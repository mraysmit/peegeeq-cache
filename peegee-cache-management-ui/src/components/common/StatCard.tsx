import { Card, Spin, Statistic, Typography } from 'antd';
import type { CSSProperties, ReactNode } from 'react';

/**
 * StatCard — metric tile (reference: `peegeeq-management-ui/components/common/StatCard.tsx`).
 *
 * Values arrive as already-formatted strings (decimal strings and byte units are formatted by the
 * presentation helpers so 64-bit precision is never lost to a JS number). The `metric-card` class
 * is the hook the browser suite filters on; the value lives in antd's `.ant-statistic-content`.
 */
export interface StatCardProps {
  readonly title: string;
  readonly value: string | number;
  readonly suffix?: string;
  readonly prefix?: ReactNode;
  readonly detail?: string;
  readonly loading?: boolean;
  readonly valueStyle?: CSSProperties;
  readonly icon?: ReactNode;
  readonly extra?: ReactNode;
}

export function StatCard({ title, value, suffix, prefix, detail, loading = false, valueStyle, icon, extra }: StatCardProps) {
  return (
    <Card className="metric-card" variant="borderless" style={{ boxShadow: '0 2px 8px rgba(0,0,0,0.06)', height: '100%' }}>
      {loading ? (
        <div style={{ textAlign: 'center', padding: '24px 0' }}><Spin /></div>
      ) : (
        <>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <Statistic
              prefix={prefix}
              suffix={suffix}
              title={title}
              value={value}
              valueStyle={{ ...valueStyle, fontSize: '24px', fontWeight: 600 }}
            />
            {icon !== undefined && <div style={{ fontSize: '32px', color: 'rgba(0, 0, 0, 0.25)' }}>{icon}</div>}
          </div>
          {(detail !== undefined || extra !== undefined) && (
            <div style={{ marginTop: '8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              {detail !== undefined && <Typography.Text type="secondary" style={{ fontSize: 14 }}>{detail}</Typography.Text>}
              {extra !== undefined && <span style={{ fontSize: '14px' }}>{extra}</span>}
            </div>
          )}
        </>
      )}
    </Card>
  );
}

export default StatCard;
