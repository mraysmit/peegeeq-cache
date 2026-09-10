import { Col, Row, Select, Typography } from 'antd';
import type { ReactNode } from 'react';

import { useSetupScopeStore } from '../../state/scope-store';
import { useListSetupsQuery } from '../../store/api/setupsApi';

interface SetupScopeBarProps {
  /** Optional content rendered in a Col immediately after the setup selector. */
  readonly extra?: ReactNode;
}

/**
 * SetupScopeBar — setup selector (reference: `peegeeq-management-ui/components/common/SetupScopeBar.tsx`).
 *
 * The setup list comes from RTK Query; the selection is owned by the Zustand scope store and is
 * committed immediately. Authorization is the server's role check, so nothing is negotiated here.
 */
export function SetupScopeBar({ extra }: SetupScopeBarProps) {
  const selectedSetupId = useSetupScopeStore((state) => state.setupId);
  const select = useSetupScopeStore((state) => state.select);
  const clear = useSetupScopeStore((state) => state.clear);
  const setups = useListSetupsQuery();

  const connected = (setups.data ?? []).filter((setup) => setup.state === 'CONNECTED');

  const choose = (setupId: string | undefined) => {
    if (setupId === undefined) clear();
    else select(setupId);
  };

  return (
    <Row align="bottom" className="scope-bar" gutter={[16, 0]} style={{ marginBottom: 16 }}>
      <Col lg={6} md={8} sm={12} xs={24}>
        <Typography.Text id="setup-scope-label" style={{ display: 'block', marginBottom: 4, fontSize: 12 }} type="secondary">Setup scope</Typography.Text>
        <Select
          allowClear
          aria-labelledby="setup-scope-label"
          loading={setups.isLoading}
          onChange={(value: string | undefined) => choose(value)}
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
