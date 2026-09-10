import type { SetupConnectionRequest, SetupRegistrationRequest } from '../../api/setup-client';
import type { SetupConnectionTest, SetupDetails, SetupHealth, SetupSummary } from '../../api/setup-schemas';
import { clientsOf, delegate } from './apiBase';
import { managementApi, setupTag } from './managementApi';

/**
 * Setup lifecycle endpoints. Reads are cached per setup; every lifecycle mutation invalidates
 * the list and the affected setup, so details (including the effective limits) are re-read on
 * state change.
 */
export const setupsApi = managementApi.injectEndpoints({
  endpoints: (build) => ({
    listSetups: build.query<SetupSummary[], void>({
      queryFn: (_arg, api) => delegate(() => clientsOf(api).setup.list()),
      providesTags: (result) => [
        { type: 'Setup', id: 'LIST' },
        ...(result ?? []).map((setup) => setupTag(setup.setupId)),
      ],
    }),
    getSetupDetails: build.query<SetupDetails, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.details(setupId)),
      providesTags: (_result, _error, { setupId }) => [setupTag(setupId)],
    }),
    getSetupHealth: build.query<SetupHealth, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.health(setupId)),
      providesTags: (_result, _error, { setupId }) => [setupTag(setupId)],
    }),
    testSetupConnection: build.mutation<SetupConnectionTest, SetupConnectionRequest>({
      queryFn: (request, api) => delegate(() => clientsOf(api).setup.testConnection(request)),
    }),
    registerSetup: build.mutation<SetupSummary, SetupRegistrationRequest>({
      queryFn: (request, api) => delegate(() => clientsOf(api).setup.register(request)),
      invalidatesTags: [{ type: 'Setup', id: 'LIST' }],
    }),
    testRegisteredSetup: build.mutation<SetupConnectionTest, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.testRegistered(setupId)),
      invalidatesTags: (_result, _error, { setupId }) => [setupTag(setupId)],
    }),
    connectSetup: build.mutation<SetupSummary, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.connect(setupId)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Setup', id: 'LIST' }, setupTag(setupId)],
    }),
    detachSetup: build.mutation<void, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.detach(setupId)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Setup', id: 'LIST' }, setupTag(setupId)],
    }),
    forgetSetup: build.mutation<void, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).setup.forget(setupId)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Setup', id: 'LIST' }, setupTag(setupId)],
    }),
  }),
});

export const {
  useListSetupsQuery,
  useGetSetupDetailsQuery,
  useLazyGetSetupDetailsQuery,
  useGetSetupHealthQuery,
  useLazyGetSetupHealthQuery,
  useTestSetupConnectionMutation,
  useRegisterSetupMutation,
  useTestRegisteredSetupMutation,
  useConnectSetupMutation,
  useDetachSetupMutation,
  useForgetSetupMutation,
} = setupsApi;
