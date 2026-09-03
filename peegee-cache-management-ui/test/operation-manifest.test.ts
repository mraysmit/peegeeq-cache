import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';

import { operationManifest } from '@src/api/operation-manifest';

interface OpenApiOperation {
  operationId?: string;
  'x-security-profile'?: string;
  'x-transport'?: string;
}

interface OpenApiDocument {
  paths: Record<string, Record<string, OpenApiOperation>>;
}

const httpMethods = new Set(['get', 'post', 'put', 'delete', 'patch', 'head', 'options', 'trace']);

function openApiOperations(): Map<string, OpenApiOperation> {
  const source = readFileSync(
    resolve(process.cwd(), '../peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml'),
    'utf8',
  );
  const document = parse(source) as OpenApiDocument;
  const operations = new Map<string, OpenApiOperation>();
  for (const pathItem of Object.values(document.paths)) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (httpMethods.has(method) && operation.operationId) {
        operations.set(operation.operationId, operation);
      }
    }
  }
  return operations;
}

describe('management UI operation manifest', () => {
  it('classifies all 60 OpenAPI operations exactly once', () => {
    const openApi = openApiOperations();
    const identifiers = operationManifest.map((operation) => operation.operationId);

    expect(openApi.size).toBe(60);
    expect(new Set(identifiers).size).toBe(identifiers.length);
    expect(new Set(identifiers)).toEqual(new Set(openApi.keys()));
  });

  it('matches the OpenAPI transport and security declarations', () => {
    const openApi = openApiOperations();

    for (const operation of operationManifest) {
      const declared = openApi.get(operation.operationId);
      expect(declared, operation.operationId).toBeDefined();
      expect(operation.securityProfile, operation.operationId).toBe(declared?.['x-security-profile']);
      expect(operation.transport, operation.operationId).toBe(declared?.['x-transport'] ?? 'rest');
    }
  });

  it('marks every reveal as sensitive and every protected POST profile as CSRF protected', () => {
    for (const operation of operationManifest) {
      if (operation.securityProfile === 'REVEAL') {
        expect(operation.sensitiveResponse, operation.operationId).toBe(true);
        expect(operation.csrfProtected, operation.operationId).toBe(true);
      }
      if (operation.securityProfile === 'OPERATE') {
        expect(operation.csrfProtected, operation.operationId).toBe(true);
      }
    }
  });
});
