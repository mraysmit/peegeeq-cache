import {
  activityPageSchema,
  databaseMonitoringSchema,
  entryMetadataSchema,
  entryPageSchema,
  revealedEntryValueSchema,
  namespaceDetailsSchema,
  namespaceExportSchema,
  namespacePageSchema,
  overviewSchema,
  runtimeMonitoringSchema,
  type ActivityPage,
  type DatabaseMonitoring,
  type EntryMetadata,
  type EntryPage,
  type RevealedEntryValue,
  type NamespaceDetails,
  type NamespaceExport,
  type NamespacePage,
  type Overview,
  type RuntimeMonitoring,
} from './inspection-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface OverviewClientPort {
  overview(setupId: string): Promise<Overview>;
}

export interface ActivityQuery {
  readonly after?: string;
  readonly limit?: number;
  readonly namespace?: string;
  readonly action?: string;
  readonly outcome?: 'SUCCEEDED' | 'REJECTED' | 'FAILED' | 'UNKNOWN';
}

export interface MonitoringClientPort {
  databaseMonitoring(setupId: string): Promise<DatabaseMonitoring>;
  runtimeMonitoring(setupId: string): Promise<RuntimeMonitoring>;
  activity(setupId: string, query?: ActivityQuery): Promise<ActivityPage>;
}

export interface NamespaceQuery {
  readonly prefix?: string;
  readonly status?: 'ALL' | 'HEALTHY' | 'EXPIRED_BACKLOG' | 'ACTIVE_LOCKS';
  readonly sort?: 'namespace:asc' | 'entryCount:desc';
  readonly cursor?: string;
  readonly limit?: number;
}

export interface NamespaceClientPort {
  namespaces(setupId: string, query?: NamespaceQuery): Promise<NamespacePage>;
  namespace(setupId: string, encodedNamespace: string): Promise<NamespaceDetails>;
  exportNamespaces(setupId: string, query?: NamespaceQuery): Promise<NamespaceExport>;
}

export interface EntryQuery {
  readonly prefix?: string;
  readonly valueType?: 'STRING' | 'JSON' | 'LONG' | 'BYTES';
  readonly ttlState?: 'ALL_LIVE' | 'PERSISTENT' | 'EXPIRING' | 'INCLUDE_EXPIRED';
  readonly cursor?: string;
  readonly limit?: number;
  readonly sort?: 'key:asc';
}

export interface EntryClientPort {
  entries(setupId: string, encodedNamespace: string, query?: EntryQuery): Promise<EntryPage>;
  entry(
    setupId: string,
    encodedNamespace: string,
    encodedKey: string,
    includeExpired?: boolean,
  ): Promise<EntryMetadata>;
}

export interface EntryRevealClientPort {
  revealEntryValue(
    setupId: string,
    encodedNamespace: string,
    encodedKey: string,
    reason?: string,
  ): Promise<RevealedEntryValue>;
}

export type EntryDetailsClientPort = EntryClientPort & EntryRevealClientPort;

export class InspectionClient implements OverviewClientPort, NamespaceClientPort, MonitoringClientPort, EntryDetailsClientPort {
  constructor(private readonly sessionClient: SessionClient) {}

  async overview(setupId: string): Promise<Overview> {
    const payload = await this.sessionClient.requestJson(
      `/api/v1/setups/${encodeURIComponent(setupId)}/overview`,
    );
    return this.parse(overviewSchema, payload);
  }

  async databaseMonitoring(setupId: string): Promise<DatabaseMonitoring> {
    const payload = await this.sessionClient.requestJson(
      `${this.setupPath(setupId)}/monitoring/database`,
    );
    return this.parse(databaseMonitoringSchema, payload);
  }

  async runtimeMonitoring(setupId: string): Promise<RuntimeMonitoring> {
    const payload = await this.sessionClient.requestJson(
      `${this.setupPath(setupId)}/monitoring/runtime`,
    );
    return this.parse(runtimeMonitoringSchema, payload);
  }

  async activity(setupId: string, query: ActivityQuery = {}): Promise<ActivityPage> {
    const payload = await this.sessionClient.requestJson(
      `${this.setupPath(setupId)}/activity${queryString(query)}`,
    );
    return this.parse(activityPageSchema, payload);
  }

  async namespaces(setupId: string, query: NamespaceQuery = {}): Promise<NamespacePage> {
    const payload = await this.sessionClient.requestJson(
      `${this.namespacesPath(setupId)}${queryString(query)}`,
    );
    return this.parse(namespacePageSchema, payload);
  }

  async namespace(setupId: string, encodedNamespace: string): Promise<NamespaceDetails> {
    const payload = await this.sessionClient.requestJson(
      `${this.namespacesPath(setupId)}/${encodeURIComponent(encodedNamespace)}`,
    );
    return this.parse(namespaceDetailsSchema, payload);
  }

  async exportNamespaces(setupId: string, query: NamespaceQuery = {}): Promise<NamespaceExport> {
    const { prefix, status, sort } = query;
    const payload = await this.sessionClient.requestJson(
      `${this.namespacesPath(setupId)}/export${queryString({ prefix, status, sort })}`,
    );
    return this.parse(namespaceExportSchema, payload);
  }

  async entries(
    setupId: string,
    encodedNamespace: string,
    query: EntryQuery = {},
  ): Promise<EntryPage> {
    const payload = await this.sessionClient.requestJson(
      `${this.entriesPath(setupId, encodedNamespace)}${queryString(query)}`,
    );
    return this.parse(entryPageSchema, payload);
  }

  async entry(
    setupId: string,
    encodedNamespace: string,
    encodedKey: string,
    includeExpired = false,
  ): Promise<EntryMetadata> {
    const payload = await this.sessionClient.requestJson(
      `${this.entriesPath(setupId, encodedNamespace)}/${encodeURIComponent(encodedKey)}${queryString({ includeExpired })}`,
    );
    return this.parse(entryMetadataSchema, payload);
  }

  async revealEntryValue(
    setupId: string,
    encodedNamespace: string,
    encodedKey: string,
    reason?: string,
  ): Promise<RevealedEntryValue> {
    const normalizedReason = reason?.trim();
    const payload = await this.sessionClient.requestSensitiveJson(
      `${this.entriesPath(setupId, encodedNamespace)}/${encodeURIComponent(encodedKey)}/value/reveal`,
      {
        body: normalizedReason === undefined || normalizedReason === ''
          ? {}
          : { reason: normalizedReason },
        method: 'POST',
      },
    );
    return this.parse(revealedEntryValueSchema, payload);
  }

  private namespacesPath(setupId: string): string {
    return `${this.setupPath(setupId)}/namespaces`;
  }

  private entriesPath(setupId: string, encodedNamespace: string): string {
    return `${this.namespacesPath(setupId)}/${encodeURIComponent(encodedNamespace)}/entries`;
  }

  private setupPath(setupId: string): string {
    return `/api/v1/setups/${encodeURIComponent(setupId)}`;
  }

  private parse<T>(
    schema: { safeParse(value: unknown): { success: true; data: T } | { success: false } },
    payload: unknown,
  ): T {
    const parsed = schema.safeParse(payload);
    if (!parsed.success) {
      throw new ManagementClientError(
        502,
        'RESPONSE_CONTRACT_INVALID',
        'The server returned an incompatible inspection response',
      );
    }
    return parsed.data;
  }
}

function queryString(query: NamespaceQuery | ActivityQuery | EntryQuery | { includeExpired?: boolean }): string {
  const parameters: string[] = [];
  const add = (name: string, value: string | number | boolean | undefined) => {
    if (value !== undefined && value !== '') {
      parameters.push(`${name}=${encodeURIComponent(String(value))}`);
    }
  };
  for (const [name, value] of Object.entries(query)) add(name, value);
  return parameters.length === 0 ? '' : `?${parameters.join('&')}`;
}
