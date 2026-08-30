import { useState } from 'react';

import type { BrowserSession } from '../../api/session-client';
import type { SetupCapabilities } from '../../api/setup-schemas';
import { loadPreferences, savePreferences, type Preferences } from '../../state/preferences';

export function SettingsPage({ capabilities, selectedSetupId, session }: { readonly capabilities?: SetupCapabilities; readonly selectedSetupId?: string; readonly session: BrowserSession }) {
  const [preferences, setPreferences] = useState(loadPreferences);
  const [saved, setSaved] = useState(false);
  const update = <K extends keyof Preferences>(name: K, value: Preferences[K]) => {
    const next = { ...preferences, [name]: value };
    setPreferences(next);
    savePreferences(next);
    setSaved(true);
  };

  return <section className="workspace" aria-labelledby="settings-title">
    <p className="workspace__context">Session and display configuration</p>
    <h1 id="settings-title">Settings</h1>
    {saved && <p role="status">Display preferences saved in this browser.</p>}
    <div className="overview-panels overview-panels--wide">
      <section className="overview-panel">
        <h2>Connection</h2>
        <dl className="compact-details">
          <Item label="Endpoint" value={globalThis.location.origin} />
          <Item label="User" value={session.user} />
          <Item label="Roles" value={session.roles.join(', ')} />
          <Item label="Authentication" value={session.authenticationMode} />
          <Item label="Server version" value={session.serverVersion} />
          <Item label="API version" value={session.apiVersion} />
          <Item label="Active setup" value={selectedSetupId ?? 'None'} />
        </dl>
      </section>
      <section className="overview-panel">
        <h2>Transport state</h2>
        <dl className="compact-details">
          <Item label="REST session" value="Connected" />
          <Item label="Metrics SSE" value="On demand on Monitoring" />
          <Item label="Monitoring WebSocket" value="On demand while notifications are open" />
          <Item label="Reconnect policy" value="Automatic bounded exponential backoff" />
        </dl>
      </section>
      {capabilities !== undefined && <section className="overview-panel">
        <h2>Effective limits</h2>
        <dl className="compact-details">
          <Item label="Maximum value bytes" value={String(capabilities.limits.maximumValueBytes)} />
          <Item label="Pub/Sub payload bytes" value={String(capabilities.limits.pubSubPayloadMaxBytes)} />
          <Item label="Pub/Sub channel bytes" value={String(capabilities.limits.pubSubChannelMaxBytes)} />
          <Item label="Migration version" value={capabilities.migrationVersion} />
        </dl>
      </section>}
    </div>
    <section className="details-section" aria-labelledby="display-title">
      <h2 id="display-title">Display preferences</h2>
      <label className="field" htmlFor="setting-theme">Theme<select id="setting-theme" onChange={(event) => update('theme', event.target.value as Preferences['theme'])} value={preferences.theme}><option value="light">Light</option><option value="dark">Dark</option></select></label>
      <label className="field" htmlFor="setting-timezone">Timezone<select id="setting-timezone" onChange={(event) => update('timezone', event.target.value as Preferences['timezone'])} value={preferences.timezone}><option value="LOCAL">Local</option><option value="UTC">UTC</option></select></label>
      <label className="field" htmlFor="setting-bytes">Byte display<select id="setting-bytes" onChange={(event) => update('byteUnits', event.target.value as Preferences['byteUnits'])} value={preferences.byteUnits}><option value="BINARY">Binary (KiB, MiB)</option><option value="DECIMAL">Decimal (kB, MB)</option></select></label>
      <label className="field" htmlFor="setting-refresh">Refresh interval<select id="setting-refresh" onChange={(event) => update('refreshSeconds', Number(event.target.value) as Preferences['refreshSeconds'])} value={preferences.refreshSeconds}><option value={15}>15 seconds</option><option value={30}>30 seconds</option><option value={60}>60 seconds</option></select></label>
      <label className="field" htmlFor="setting-auto-hide">Masked-value auto-hide<select id="setting-auto-hide" onChange={(event) => update('autoHideSeconds', Number(event.target.value) as Preferences['autoHideSeconds'])} value={preferences.autoHideSeconds}><option value={30}>30 seconds</option><option value={60}>60 seconds</option><option value={120}>120 seconds</option></select></label>
    </section>
    <p>Only display preferences are stored. Credentials, revealed values, owner tokens, payloads, setup secrets, and live notifications are never persisted.</p>
  </section>;
}

function Item({ label, value }: { readonly label: string; readonly value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}
