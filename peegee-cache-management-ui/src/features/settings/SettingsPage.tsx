import { Alert, Card, Col, Descriptions, Form, Row, Space, Typography } from 'antd';
import { useState } from 'react';

import type { BrowserSession } from '../../api/session-client';
import type { SetupCapabilities } from '../../api/setup-schemas';
import { ValueSelect } from '../../components/common/ValueSelect';
import { loadPreferences, savePreferences, type Preferences } from '../../state/preferences';
import {
  AUTO_HIDE_OPTIONS,
  BYTE_UNIT_OPTIONS,
  REFRESH_OPTIONS,
  THEME_OPTIONS,
  TIMEZONE_OPTIONS,
  type AutoHideSeconds,
  type RefreshSeconds,
} from './settings-options';

const { Title, Text, Paragraph } = Typography;

interface SettingsPageProps {
  readonly capabilities?: SetupCapabilities;
  readonly selectedSetupId?: string;
  readonly session: BrowserSession;
}

/**
 * Settings (reference layout: Descriptions cards for effective state, a Form of Selects for the
 * allowlisted display preferences). Only display preferences are persisted, through the
 * allowlisting `savePreferences`; every other value on the page is read from the session or the
 * selected setup's capability snapshot.
 */
export function SettingsPage({ capabilities, selectedSetupId, session }: SettingsPageProps) {
  const [preferences, setPreferences] = useState(loadPreferences);
  const [saved, setSaved] = useState(false);
  const update = <K extends keyof Preferences>(name: K, value: Preferences[K]) => {
    const next = { ...preferences, [name]: value };
    setPreferences(next);
    savePreferences(next);
    setSaved(true);
  };

  return (
    <section aria-labelledby="settings-title" className="workspace">
      <div className="workspace__heading" style={{ marginBottom: 16 }}>
        <Text className="workspace__context" type="secondary">Session and display configuration</Text>
        <Title id="settings-title" level={1} style={{ marginTop: 4 }}>Settings</Title>
      </div>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {saved && <Alert message="Display preferences saved in this browser." role="status" showIcon type="success" />}
        <Row gutter={[16, 16]}>
          <Col lg={8} sm={12} xs={24}>
            <Card className="overview-panel" title={<Title level={2} style={{ margin: 0, fontSize: 18 }}>Connection</Title>}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="Endpoint">{globalThis.location.origin}</Descriptions.Item>
                <Descriptions.Item label="User">{session.user}</Descriptions.Item>
                <Descriptions.Item label="Roles">{session.roles.join(', ')}</Descriptions.Item>
                <Descriptions.Item label="Authentication">{session.authenticationMode}</Descriptions.Item>
                <Descriptions.Item label="Server version">{session.serverVersion}</Descriptions.Item>
                <Descriptions.Item label="API version">{session.apiVersion}</Descriptions.Item>
                <Descriptions.Item label="Active setup">{selectedSetupId ?? 'None'}</Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>
          <Col lg={8} sm={12} xs={24}>
            <Card className="overview-panel" title={<Title level={2} style={{ margin: 0, fontSize: 18 }}>Transport state</Title>}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="REST session">Connected</Descriptions.Item>
                <Descriptions.Item label="Metrics SSE">On demand on Monitoring</Descriptions.Item>
                <Descriptions.Item label="Monitoring WebSocket">On demand while notifications are open</Descriptions.Item>
                <Descriptions.Item label="Reconnect policy">Automatic bounded exponential backoff</Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>
          {capabilities !== undefined && (
            <Col lg={8} sm={12} xs={24}>
              <Card className="overview-panel" title={<Title level={2} style={{ margin: 0, fontSize: 18 }}>Effective limits</Title>}>
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="Maximum value bytes">{String(capabilities.limits.maximumValueBytes)}</Descriptions.Item>
                  <Descriptions.Item label="Pub/Sub payload bytes">{String(capabilities.limits.pubSubPayloadMaxBytes)}</Descriptions.Item>
                  <Descriptions.Item label="Pub/Sub channel bytes">{String(capabilities.limits.pubSubChannelMaxBytes)}</Descriptions.Item>
                  <Descriptions.Item label="Migration version">{capabilities.migrationVersion}</Descriptions.Item>
                </Descriptions>
              </Card>
            </Col>
          )}
        </Row>
        <section aria-labelledby="display-title" className="details-section">
          <Card title={<Title id="display-title" level={2} style={{ margin: 0, fontSize: 18 }}>Display preferences</Title>}>
            <Form className="form-grid" layout="vertical">
              <Form.Item htmlFor="setting-theme" label="Theme">
                <ValueSelect<Preferences['theme']> id="setting-theme" onChange={(value) => update('theme', value)} options={THEME_OPTIONS} value={preferences.theme} />
              </Form.Item>
              <Form.Item htmlFor="setting-timezone" label="Timezone">
                <ValueSelect<Preferences['timezone']> id="setting-timezone" onChange={(value) => update('timezone', value)} options={TIMEZONE_OPTIONS} value={preferences.timezone} />
              </Form.Item>
              <Form.Item htmlFor="setting-bytes" label="Byte display">
                <ValueSelect<Preferences['byteUnits']> id="setting-bytes" onChange={(value) => update('byteUnits', value)} options={BYTE_UNIT_OPTIONS} value={preferences.byteUnits} />
              </Form.Item>
              <Form.Item htmlFor="setting-refresh" label="Refresh interval">
                <ValueSelect<RefreshSeconds> id="setting-refresh" onChange={(value) => update('refreshSeconds', Number(value) as Preferences['refreshSeconds'])} options={REFRESH_OPTIONS} value={String(preferences.refreshSeconds) as RefreshSeconds} />
              </Form.Item>
              <Form.Item htmlFor="setting-auto-hide" label="Masked-value auto-hide">
                <ValueSelect<AutoHideSeconds> id="setting-auto-hide" onChange={(value) => update('autoHideSeconds', Number(value) as Preferences['autoHideSeconds'])} options={AUTO_HIDE_OPTIONS} value={String(preferences.autoHideSeconds) as AutoHideSeconds} />
              </Form.Item>
            </Form>
          </Card>
        </section>
        <Paragraph>Only display preferences are stored. Credentials, revealed values, owner tokens, payloads, setup secrets, and live notifications are never persisted.</Paragraph>
      </Space>
    </section>
  );
}
