import { useMemo, useState, type ReactNode } from 'react';

import type { CacheValue } from '../../api/inspection-schemas';

export function EntryValueFormatter({ value }: { readonly value: CacheValue }) {
  if (value.type === 'STRING') return <StringFormatter text={value.text} />;
  if (value.type === 'JSON') return <JsonFormatter key={value.text} text={value.text} />;
  if (value.type === 'LONG') return <pre className="value-content">{value.decimal}</pre>;
  return <BytesFormatter base64={value.base64} key={value.base64} />;
}

function StringFormatter({ text }: { readonly text: string }) {
  const [escaped, setEscaped] = useState(false);
  return <FormatterFrame controls={<><ViewButton active={!escaped} label="UTF-8 text" onClick={() => setEscaped(false)} /><ViewButton active={escaped} label="Escaped text" onClick={() => setEscaped(true)} /></>}><pre className="value-content">{escaped ? escapeText(text) : text}</pre></FormatterFrame>;
}

function JsonFormatter({ text }: { readonly text: string }) {
  const parsed = useMemo(() => parseJson(text), [text]);
  const [view, setView] = useState<'tree' | 'formatted' | 'raw'>('tree');
  if (!parsed.valid) return <><p className="validation validation--invalid">Invalid JSON</p><pre className="value-content">{text}</pre></>;
  return <><p className="validation validation--valid">Valid JSON</p><FormatterFrame controls={<><ViewButton active={view === 'tree'} label="Tree" onClick={() => setView('tree')} /><ViewButton active={view === 'formatted'} label="Formatted text" onClick={() => setView('formatted')} /><ViewButton active={view === 'raw'} label="Raw UTF-8" onClick={() => setView('raw')} /></>}>{view === 'tree' ? <JsonTree value={parsed.value} /> : <pre className="value-content">{view === 'formatted' ? JSON.stringify(parsed.value, null, 2) : text}</pre>}</FormatterFrame></>;
}

function BytesFormatter({ base64 }: { readonly base64: string }) {
  const bytes = useMemo(() => decodeBase64(base64), [base64]);
  const [view, setView] = useState<'hex' | 'base64' | 'utf8'>('hex');
  const utf8 = decodeUtf8(bytes);
  const content = view === 'hex'
    ? Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join(' ')
    : view === 'base64' ? base64 : utf8 ?? 'Not valid UTF-8';
  return <><p className="value-size">{bytes.byteLength.toLocaleString('en-US')} {bytes.byteLength === 1 ? 'byte' : 'bytes'}</p><FormatterFrame controls={<><ViewButton active={view === 'hex'} label="Hex" onClick={() => setView('hex')} /><ViewButton active={view === 'base64'} label="Base64" onClick={() => setView('base64')} /><ViewButton active={view === 'utf8'} label="UTF-8 attempt" onClick={() => setView('utf8')} /></>}><pre className="value-content">{content}</pre></FormatterFrame></>;
}

function FormatterFrame({ controls, children }: { readonly controls: ReactNode; readonly children: ReactNode }) {
  return <div className="value-formatter"><div className="value-formatter__controls" role="group" aria-label="Value format">{controls}</div>{children}</div>;
}

function ViewButton({ active, label, onClick }: { readonly active: boolean; readonly label: string; readonly onClick: () => void }) {
  return <button aria-pressed={active} className="button button--secondary" onClick={onClick} type="button">{label}</button>;
}

function JsonTree({ value }: { readonly value: unknown }) {
  if (typeof value === 'string') return <code>{value}</code>;
  if (value === null || typeof value !== 'object') return <code>{JSON.stringify(value)}</code>;
  const entries = Object.entries(value as Record<string, unknown>);
  return <dl className="json-tree">{entries.map(([key, child]) => <div key={key}><dt>{key}</dt><dd><JsonTree value={child} /></dd></div>)}</dl>;
}

function parseJson(text: string): { valid: true; value: unknown } | { valid: false } {
  try { return { valid: true, value: JSON.parse(text) as unknown }; } catch { return { valid: false }; }
}

function escapeText(text: string): string { return JSON.stringify(text).slice(1, -1); }
function decodeBase64(value: string): Uint8Array { return Uint8Array.from(atob(value), (character) => character.charCodeAt(0)); }
function decodeUtf8(bytes: Uint8Array): string | undefined {
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { return undefined; }
}
