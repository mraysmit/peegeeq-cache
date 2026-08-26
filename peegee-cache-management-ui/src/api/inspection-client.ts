import {
  namespaceDetailsSchema,
  namespaceExportSchema,
  namespacePageSchema,
  overviewSchema,
  type NamespaceDetails,
  type NamespaceExport,
  type NamespacePage,
  type Overview,
} from './inspection-schemas';
import { ManagementClientError, SessionClient } from './session-client';

export interface OverviewClientPort {
  overview(setupId: string): Promise<Overview>;
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

export class InspectionClient implements OverviewClientPort, NamespaceClientPort {
  constructor(private readonly sessionClient: SessionClient) {}

  async overview(setupId: string): Promise<Overview> {
    const payload = await this.sessionClient.requestJson(
      `/api/v1/setups/${encodeURIComponent(setupId)}/overview`,
    );
    return this.parse(overviewSchema, payload);
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

  private namespacesPath(setupId: string): string {
    return `/api/v1/setups/${encodeURIComponent(setupId)}/namespaces`;
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

function queryString(query: NamespaceQuery): string {
  const parameters: string[] = [];
  const add = (name: string, value: string | number | undefined) => {
    if (value !== undefined && value !== '') {
      parameters.push(`${name}=${encodeURIComponent(String(value))}`);
    }
  };
  add('prefix', query.prefix);
  add('status', query.status);
  add('sort', query.sort);
  add('cursor', query.cursor);
  add('limit', query.limit);
  return parameters.length === 0 ? '' : `?${parameters.join('&')}`;
}
