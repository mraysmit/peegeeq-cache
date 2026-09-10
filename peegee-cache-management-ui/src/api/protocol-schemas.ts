import { z } from 'zod';

import type { CurrentSessionContract, ManagementProblemContract } from './openapi-contract';

export const utcInstantSchema = z.string().refine(
  (value) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
    && Number.isFinite(Date.parse(value)),
  'must be a UTC ISO-8601 instant',
);

const rolesSchema = z.array(z.enum(['viewer', 'operator'])).min(1).superRefine((roles, context) => {
  if (new Set(roles).size !== roles.length) {
    context.addIssue({ code: 'custom', message: 'roles must be unique' });
  }
});

export const currentSessionSchema: z.ZodType<CurrentSessionContract> = z.strictObject({
  user: z.string().min(1).max(256),
  roles: rolesSchema,
  serverVersion: z.string(),
  apiVersion: z.literal('v1'),
  authenticationMode: z.enum(['TRUSTED_PROXY', 'LOCAL_TOKEN']),
  csrfToken: z.string().min(32).max(512),
  sessionIdleExpiresAt: utcInstantSchema,
  sessionExpiresAt: utcInstantSchema,
});

export const managementProblemSchema: z.ZodType<ManagementProblemContract> = z.strictObject({
  type: z.url(),
  title: z.string(),
  status: z.number().int().min(400).max(599),
  code: z.string(),
  detail: z.string(),
  instance: z.string(),
  correlationId: z.string(),
  fieldErrors: z.array(z.strictObject({
    field: z.string().min(1).max(256),
    message: z.string().min(1).max(512),
  })).max(100),
});

export type CurrentSession = z.infer<typeof currentSessionSchema>;
export type ManagementProblem = z.infer<typeof managementProblemSchema>;
