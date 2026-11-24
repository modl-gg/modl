import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  CheckPublicUrlRequestSchema,
  CheckPublicUrlResponseSchema,
  PublicUrlVerdict,
  SetupHost,
  SetupPhase,
  SetupStatusResponseSchema,
  SetupStep,
  SetupStepStatus,
  SetupWizardStateSchema,
  UpdateSetupStepRequestSchema,
  type SetupStepState,
  type SetupWizardState,
} from '@proto/modl/v1/setup_pb.ts';
import { protoFetch, protoSend } from '@/lib/proto-fetch';
import {
  orderedStepProgress,
  type SetupStepId,
  type SetupStepProgress,
  type SetupStepStatusId,
} from '@/lib/setup-steps';
import type { QueryGate } from './query-gate';

export const SETUP_STATUS_QUERY_KEY = ['/v1/public/setup/status'] as const;
const SETUP_WIZARD_PATH = '/v1/panel/setup';
const SETUP_WIZARD_QUERY_KEY = [SETUP_WIZARD_PATH] as const;
const PUBLIC_URL_CHECK_PATH = '/v1/panel/setup/public-url/check';

export type SetupPhaseId = 'unclaimed' | 'owner-sign-in' | 'ready';
export type SetupHostId = 'plugin' | 'standalone';

export interface SetupStatus {
  phase: SetupPhaseId;
}

export interface SetupEnvironment {
  host: SetupHostId;
  reverseProxySuspected: boolean;
}

export interface SetupWizard {
  completed: boolean;
  steps: SetupStepProgress[];
  environment: SetupEnvironment;
}

export type PublicUrlReachability = 'reachable' | 'unreachable' | 'other-service' | 'invalid';

export interface PublicUrlCheck {
  reachability: PublicUrlReachability;
}

const PHASES: ReadonlyMap<SetupPhase, SetupPhaseId> = new Map<SetupPhase, SetupPhaseId>([
  [SetupPhase.UNSPECIFIED, 'ready'],
  [SetupPhase.UNCLAIMED, 'unclaimed'],
  [SetupPhase.OWNER_SIGN_IN, 'owner-sign-in'],
  [SetupPhase.READY, 'ready'],
]);

const STEPS: ReadonlyMap<SetupStep, SetupStepId | null> = new Map<SetupStep, SetupStepId | null>([
  [SetupStep.UNSPECIFIED, null],
  [SetupStep.ACCOUNT, 'account'],
  [SetupStep.IMPORT, 'import'],
  [SetupStep.IDENTITY, 'identity'],
  [SetupStep.ADDRESS, 'address'],
  [SetupStep.EMAIL, 'email'],
  [SetupStep.SERVERS, 'servers'],
  [SetupStep.MINECRAFT_ACCOUNT, 'minecraft-account'],
  [SetupStep.STAFF, 'staff'],
  [SetupStep.PLAYER_ADDRESSES, 'player-addresses'],
  [SetupStep.AI, 'ai'],
  [SetupStep.CONTENT, 'content'],
]);

const STEP_STATUSES: ReadonlyMap<SetupStepStatus, SetupStepStatusId> = new Map<SetupStepStatus, SetupStepStatusId>([
  [SetupStepStatus.UNSPECIFIED, 'pending'],
  [SetupStepStatus.PENDING, 'pending'],
  [SetupStepStatus.DONE, 'done'],
  [SetupStepStatus.SKIPPED, 'pending'],
]);

const REACHABILITY: ReadonlyMap<PublicUrlVerdict, PublicUrlReachability> = new Map<PublicUrlVerdict, PublicUrlReachability>([
  [PublicUrlVerdict.UNSPECIFIED, 'unreachable'],
  [PublicUrlVerdict.REACHABLE, 'reachable'],
  [PublicUrlVerdict.UNREACHABLE, 'unreachable'],
  [PublicUrlVerdict.OTHER_SERVICE, 'other-service'],
  [PublicUrlVerdict.INVALID, 'invalid'],
]);

function toHost(host: SetupHost): SetupHostId {
  return host === SetupHost.PLUGIN ? 'plugin' : 'standalone';
}

function toStepProgress(state: SetupStepState): SetupStepProgress | null {
  const step = STEPS.get(state.step) ?? null;
  return step === null ? null : { step, status: STEP_STATUSES.get(state.status) ?? 'pending' };
}

function toSetupWizard(state: SetupWizardState): SetupWizard {
  const reported = state.steps.map(toStepProgress).filter((entry): entry is SetupStepProgress => entry !== null);
  return {
    completed: state.completed,
    steps: orderedStepProgress(reported),
    environment: {
      host: toHost(state.environment?.host ?? SetupHost.UNSPECIFIED),
      reverseProxySuspected: state.environment?.reverseProxySuspected ?? false,
    },
  };
}

export function useSetupStatus() {
  return useQuery({
    queryKey: SETUP_STATUS_QUERY_KEY,
    queryFn: async (): Promise<SetupStatus> => {
      const status = await protoFetch(SetupStatusResponseSchema, '/v1/public/setup/status');
      return { phase: PHASES.get(status.phase) ?? 'ready' };
    },
    staleTime: 30_000,
    retry: 1,
  });
}

export function useSetupWizard({ enabled = true }: QueryGate = {}) {
  return useQuery({
    queryKey: SETUP_WIZARD_QUERY_KEY,
    enabled,
    queryFn: async () => toSetupWizard(await protoFetch(SetupWizardStateSchema, SETUP_WIZARD_PATH)),
    staleTime: 60_000,
    retry: 1,
  });
}

export function useRecordSetupStep() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (step: SetupStepId) => toSetupWizard(await protoSend(
      'PUT',
      `${SETUP_WIZARD_PATH}/steps/${step}`,
      UpdateSetupStepRequestSchema,
      create(UpdateSetupStepRequestSchema, { status: SetupStepStatus.DONE }),
      SetupWizardStateSchema,
    )),
    onSuccess: (wizard) => {
      queryClient.setQueryData(SETUP_WIZARD_QUERY_KEY, wizard);
      void queryClient.invalidateQueries({ queryKey: SETUP_STATUS_QUERY_KEY });
    },
  });
}

export function usePublicUrlCheck(url: string) {
  return useQuery({
    queryKey: [PUBLIC_URL_CHECK_PATH, url],
    enabled: url.length > 0,
    queryFn: async (): Promise<PublicUrlCheck> => {
      const result = await protoSend(
        'POST',
        PUBLIC_URL_CHECK_PATH,
        CheckPublicUrlRequestSchema,
        create(CheckPublicUrlRequestSchema, { url }),
        CheckPublicUrlResponseSchema,
      );
      return { reachability: REACHABILITY.get(result.verdict) ?? 'unreachable' };
    },
    staleTime: 30_000,
    retry: false,
    refetchOnWindowFocus: false,
  });
}
