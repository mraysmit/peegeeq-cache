import { z } from 'zod';

import type {
  SetupConnectionTestContract,
  SetupDetailsContract,
  SetupHealthContract,
  SetupLimitsContract,
  SetupSummaryContract,
  SetupSummaryListContract,
} from './openapi-contract';
import { utcInstantSchema } from './protocol-schemas';

const nonNegativeDecimalSchema = z.string().regex(/^(?:0|[1-9][0-9]*)$/u);

const setupHealthSummarySchema = z.strictObject({
  status: z.enum(['UP', 'DOWN', 'DEGRADED']),
  latencyMillis: z.number().int().nonnegative(),
  checkedAt: utcInstantSchema,
});

export const setupSummarySchema: z.ZodType<SetupSummaryContract> = z.strictObject({
  setupId: z.string().regex(/^[a-z][a-z0-9-]{0,62}$/u),
  displayName: z.string().min(1).max(128),
  host: z.string(),
  port: z.number().int().min(1).max(65_535),
  database: z.string(),
  schema: z.string(),
  sslMode: z.literal('VERIFY_FULL'),
  source: z.enum(['CONFIGURED', 'UI_SESSION']),
  state: z.enum(['CONNECTING', 'CONNECTED', 'DETACHING', 'DETACHED', 'UNHEALTHY']),
  schemaState: z.enum(['READY', 'MISSING', 'OUTDATED', 'UNKNOWN']),
  lastHealth: setupHealthSummarySchema.nullable(),
});

export const setupSummaryListSchema: z.ZodType<SetupSummaryListContract> = z.strictObject({
  items: z.array(setupSummarySchema).max(1_000),
});

export const setupLimitsSchema: z.ZodType<SetupLimitsContract> = z.strictObject({
  pubSubChannelMaxBytes: z.number().int().min(1).max(63),
  pubSubPayloadMaxBytes: z.number().int().min(1),
  maximumValueBytes: z.number().int().min(1),
});

export const setupConnectionTestSchema: z.ZodType<SetupConnectionTestContract> = z.strictObject({
  databaseReachable: z.boolean(),
  schemaState: z.enum(['READY', 'MISSING', 'OUTDATED', 'UNKNOWN']),
  migrationVersion: nonNegativeDecimalSchema,
  latencyMillis: z.number().int().nonnegative(),
  limits: setupLimitsSchema,
});

export const setupDetailsSchema: z.ZodType<SetupDetailsContract> = z.strictObject({
  setup: setupSummarySchema,
  migrationVersion: nonNegativeDecimalSchema,
  limits: setupLimitsSchema,
  runtime: z.strictObject({
    defaultTtlMillis: z.number().int().min(1).nullable(),
    expirySweeperEnabled: z.boolean(),
    expirySweepIntervalMillis: z.number().int().min(1),
    expirySweepBatchSize: z.number().int().min(1),
    writeBehindEnabled: z.boolean(),
    writeBehindFlushIntervalMillis: z.number().int().min(1),
    writeBehindMaxBufferSize: z.number().int().min(100),
    writeBehindFlushBatchSize: z.number().int().min(1),
    writeBehindMaxRetries: z.number().int().nonnegative(),
    writeBehindShutdownDrainTimeoutMillis: z.number().int().min(1),
    pubSubChannelPrefix: z.string().min(1).max(48),
    pubSubEnabled: z.boolean(),
    schemaBootstrapMode: z.enum(['EXTERNAL', 'APPLY']),
    telemetryMode: z.literal('NOOP'),
    poolMaxSize: z.number().int().min(1),
  }),
  registeredAt: utcInstantSchema,
  connectedAt: utcInstantSchema.nullable(),
});

export const setupHealthSchema: z.ZodType<SetupHealthContract> = z.strictObject({
  status: z.enum(['UP', 'DOWN', 'DEGRADED']),
  schemaReady: z.boolean(),
  latencyMillis: z.number().int().nonnegative(),
  checkedAt: utcInstantSchema,
  detail: z.string().max(512),
});

export type SetupSummary = z.infer<typeof setupSummarySchema>;
export type SetupDetails = z.infer<typeof setupDetailsSchema>;
export type SetupLimits = z.infer<typeof setupLimitsSchema>;
export type SetupConnectionTest = z.infer<typeof setupConnectionTestSchema>;
export type SetupHealth = z.infer<typeof setupHealthSchema>;
