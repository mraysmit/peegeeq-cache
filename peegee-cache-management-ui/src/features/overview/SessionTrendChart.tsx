export interface SessionTrendPoint {
  readonly expiredEntries: string;
  readonly liveEntries: string;
  readonly observedAt: string;
}

export function SessionTrendChart({ points }: { readonly points: readonly SessionTrendPoint[] }) {
  const allValues = points.flatMap((point) => [BigInt(point.liveEntries), BigInt(point.expiredEntries)]);
  const maximum = allValues.reduce((current, value) => value > current ? value : current, 0n);
  const live = polyline(points.map((point) => BigInt(point.liveEntries)), maximum);
  const expired = polyline(points.map((point) => BigInt(point.expiredEntries)), maximum);
  return (
    <section className="trend-panel" aria-label="Current-session cache row trend">
      <div className="section-heading">
        <div><p className="panel-scope">Database-wide · current console session only</p><h2>Live and expired entry trend</h2></div>
        <p>{points.length} {points.length === 1 ? 'snapshot' : 'snapshots'}</p>
      </div>
      <svg aria-hidden="true" className="trend-chart" preserveAspectRatio="none" viewBox="0 0 100 100">
        <line className="trend-chart__axis" x1="0" x2="100" y1="90" y2="90" />
        <polyline className="trend-chart__live" points={live} />
        <polyline className="trend-chart__expired" points={expired} />
      </svg>
      <div className="trend-legend">
        <span><i className="trend-legend__live" aria-hidden="true" />Live entries</span>
        <span><i className="trend-legend__expired" aria-hidden="true" />Expired entries</span>
      </div>
      {points.at(-1) !== undefined && (
        <p className="trend-latest">Latest: {BigInt(points.at(-1)!.liveEntries).toLocaleString('en-US')} live, {BigInt(points.at(-1)!.expiredEntries).toLocaleString('en-US')} expired</p>
      )}
    </section>
  );
}

function polyline(values: readonly bigint[], maximum: bigint): string {
  if (values.length === 0) return '';
  return values.map((value, index) => {
    const x = values.length === 1 ? 50 : index * 100 / (values.length - 1);
    const y = maximum === 0n ? 90 : 90 - Number(value * 80n / maximum);
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(' ');
}
