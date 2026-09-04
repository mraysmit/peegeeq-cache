import { Badge } from 'antd';

/**
 * Header connection indicator (reference: `components/common/ConnectionStatus`). Renders the
 * exact label text the browser suite asserts ("Connected", "Live", "Live stale", "Connecting").
 */
export function ConnectionStatus({ label, state }: {
  readonly label: string;
  readonly state: 'connected' | 'warning' | 'error';
}) {
  const status = state === 'connected' ? 'success' : state === 'warning' ? 'warning' : 'error';
  return <Badge className="connection-status" status={status} text={label} />;
}
