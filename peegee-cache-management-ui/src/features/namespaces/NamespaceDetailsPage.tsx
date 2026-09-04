import { Alert, Card, Col, Descriptions, Empty, Row, Tabs, Typography } from 'antd';
import { useEffect, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { StatCard } from '../../components/common/StatCard';
import { formatDecimal, humanize } from '../../presentation/display-format';
import { formatDisplayInstant } from '../../presentation/display-time';
import { isManagementQueryError } from '../../store/api/apiBase';
import { useGetNamespaceQuery } from '../../store/api/inspectionApi';

const { Title, Text } = Typography;

interface NamespaceDetailsPageProps {
  readonly encodedNamespace: string;
  readonly selectedSetupId?: string;
  readonly onSelectNamespace: (namespace: string) => void;
}

/**
 * Namespace details (reference layout: StatCard row, Tabs to sibling resources, Descriptions
 * panels). The route carries the server-encoded namespace; the decoded name comes back from
 * the contract and is what the scope store receives.
 */
export function NamespaceDetailsPage({ encodedNamespace, selectedSetupId, onSelectNamespace }: NamespaceDetailsPageProps) {
  const navigate = useNavigate();
  const details = useGetNamespaceQuery(
    { setupId: selectedSetupId ?? '', encodedNamespace },
    { skip: selectedSetupId === undefined },
  );
  const namespace = details.data?.stats.namespace;
  useEffect(() => {
    if (namespace !== undefined) onSelectNamespace(namespace);
  }, [namespace, onSelectNamespace]);

  if (selectedSetupId === undefined) {
    return <DetailsWorkspace title="Namespace details"><Card><Empty description={<Title level={2} style={{ fontSize: 18 }}>Select a connected setup</Title>} /></Card></DetailsWorkspace>;
  }
  const problem = isManagementQueryError(details.error) ? details.error : undefined;
  if (problem !== undefined && details.data === undefined) {
    return <DetailsWorkspace title="Namespace details"><Alert description={problem.message} message={problem.code} role="alert" showIcon type="error" /></DetailsWorkspace>;
  }
  if (details.data === undefined) {
    return <DetailsWorkspace title="Namespace details"><Text aria-busy="true">Loading namespace details…</Text></DetailsWorkspace>;
  }

  const { stats, valueTypeCounts, ttlStateCounts, ttlDistribution } = details.data;
  return (
    <DetailsWorkspace title={stats.namespace}>
      <Text className="scope-label" type="secondary">Database-wide · Observed {formatDisplayInstant(stats.observedAt)}</Text>
      <Row aria-label="Namespace totals" gutter={[16, 16]} role="group" style={{ marginTop: 16 }}>
        <Col lg={4} sm={8} xs={12}><StatCard title="Live entries" value={formatDecimal(stats.liveEntryCount)} /></Col>
        <Col lg={4} sm={8} xs={12}><StatCard title="Live counters" value={formatDecimal(stats.liveCounterCount)} /></Col>
        <Col lg={4} sm={8} xs={12}><StatCard title="Active locks" value={formatDecimal(stats.activeLockCount)} /></Col>
        <Col lg={4} sm={8} xs={12}><StatCard title="Expiring entries" value={formatDecimal(stats.expiringEntryCount)} /></Col>
        <Col lg={4} sm={8} xs={12}><StatCard title="Expired entries" value={formatDecimal(stats.expiredEntryCount)} /></Col>
        <Col lg={4} sm={8} xs={12}><StatCard suffix="B" title="Estimated storage" value={formatDecimal(stats.estimatedStorageBytes)} /></Col>
      </Row>
      <Tabs
        activeKey="overview"
        aria-label="Namespace resources"
        items={[
          { key: 'overview', label: 'Overview' },
          { key: 'entries', label: 'Entries' },
          { key: 'counters', label: 'Counters' },
          { key: 'locks', label: 'Locks' },
        ]}
        onChange={(key) => {
          if (key === 'entries') void navigate('/keys');
          if (key === 'counters') void navigate(`/counters?namespace=${stats.encodedNamespace}`);
          if (key === 'locks') void navigate(`/locks?namespace=${stats.encodedNamespace}`);
        }}
        style={{ marginTop: 16 }}
      />
      <Row gutter={[16, 16]}>
        <Col lg={8} xs={24}>
          <DetailPanel heading="Value types">
            {Object.entries(valueTypeCounts).map(([type, count]) => <Descriptions.Item key={type} label={type}>{formatDecimal(count)}</Descriptions.Item>)}
          </DetailPanel>
        </Col>
        <Col lg={8} xs={24}>
          <DetailPanel heading="TTL states">
            {Object.entries(ttlStateCounts).map(([state, count]) => <Descriptions.Item key={state} label={humanize(state)}>{formatDecimal(count)}</Descriptions.Item>)}
          </DetailPanel>
        </Col>
        <Col lg={8} xs={24}>
          <DetailPanel heading="TTL distribution">
            {ttlDistribution.map((bucket) => <Descriptions.Item key={bucket.range} label={humanize(bucket.range)}>{formatDecimal(bucket.count)}</Descriptions.Item>)}
          </DetailPanel>
        </Col>
      </Row>
    </DetailsWorkspace>
  );
}

function DetailPanel({ heading, children }: { readonly heading: string; readonly children: ReactNode }) {
  const id = `namespace-${heading.toLowerCase().replaceAll(/[^a-z0-9]+/gu, '-')}-heading`;
  return (
    <section aria-labelledby={id} className="overview-panel">
      <Card title={<Title id={id} level={2} style={{ margin: 0, fontSize: 18 }}>{heading}</Title>}>
        <Descriptions column={1} size="small">{children}</Descriptions>
      </Card>
    </section>
  );
}

function DetailsWorkspace({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  return (
    <section aria-labelledby="namespace-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary"><Link to="/namespaces">Namespaces</Link></Text>
        <Title id="namespace-title" level={1} style={{ marginTop: 4 }}>{title}</Title>
      </div>
      {children}
    </section>
  );
}
