import { Col, Row, Select, Typography, message } from 'antd';
import type { ReactNode } from 'react';

import { useSetupScopeStore } from '../../state/scope-store';
import { useLazyGetSetupCapabilitiesQuery, useListSetupsQuery } from '../../store/api/setupsApi';

interface SetupScopeBarProps {
  /** Optional content rendered in a Col immediately after the setup selector. */
  readonly extra?: ReactNode;
}

/**
 * SetupScopeBar — setup selector (reference: `peegeeq-management-ui/components/common/SetupScopeBar.tsx`).
 *
 * The setup list comes from RTK Query; the selection is owned by the Zustand scope store, which
 * also holds the capability snapshot the shell gates routes on, so selecting a setup here fetches
 * its capabilities first and only then commits the scope (design §8.2).
 */
export function SetupScopeBar({ extra }: SetupScopeBarProps) {
  const selectedSetupId = useSetupScopeStore((state) => state.setupId);
  const select = useSetupScopeStore((state) => state.select);
  const clear = useSetupScopeStore((state) => state.clear);
  const setups = useListSetupsQuery();
  const [loadCapabilities, capabilityLookup] = useLazyGetSetupCapabilitiesQuery();
  const [messageApi, contextHolder] = message.useMessage();

  const connected = (setups.data ?? []).filter((setup) => setup.state === 'CONNECTED');

  const choose = async (setupId: string | undefined) => {
    if (setupId === undefined) {
      clear();
      return;
    }
    try {
      const capabilities = await loadCapabilities({ setupId }).unwrap();
      select(setupId, capabilities);
    } catch {
      void messageApi.error(`Capabilities for ${setupId} could not be loaded`);
    }
  };

  return (
    <Row align="bottom" className="scope-bar" gutter={[16, 0]} style={{ marginBottom: 16 }}>
      {contextHolder}
      <Col lg={6} md={8} sm={12} xs={24}>
        <Typography.Text id="setup-scope-label" style={{ display: 'block', marginBottom: 4, fontSize: 12 }} type="secondary">Setup scope</Typography.Text>
        <Select
          allowClear
          aria-labelledby="setup-scope-label"
          loading={setups.isLoading || capabilityLookup.isLoading}
          onChange={(value: string | undefined) => void choose(value)}
          optionFilterProp="label"
          options={connected.map((setup) => ({ label: `${setup.displayName} (${setup.setupId})`, value: setup.setupId }))}
          placeholder="Select a connected setup"
          showSearch
          style={{ width: '100%' }}
          value={selectedSetupId}
        />
      </Col>
      {extra !== undefined && <Col flex="none">{extra}</Col>}
    </Row>
  );
}

export default SetupScopeBar;
