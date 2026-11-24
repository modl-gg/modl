import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from '@bufbuild/protobuf';
import {
  PanelAuthResponseSchema,
  PanelConsumeLoginLinkRequestSchema,
  PanelMinecraftLinkCodeResponseSchema,
  PanelProfileResponseSchema,
  PanelSessionsResponseSchema,
  PanelUpdateEmailRequestSchema,
  PanelUpdateEmailWithCodeRequestSchema,
  PanelUpdateProfileRequestSchema,
  type PanelSessionInfoResponse,
} from '@proto/modl/v1/auth_pb.ts';
import {
  Staff2faPendingApprovalResponseSchema,
  Staff2faVerifyRequestSchema,
  Staff2faVerifyResponseSchema,
} from '@proto/modl/v1/staff_pb.ts';
import { apiFetch } from '@/lib/api';
import { protoFetch, protoHttpErrorOf, protoSend } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import type { CredentialProofInput } from './sign-in';
import { forgetMinecraftAccount, invalidateSignedInProfile } from './signed-in-profile';

const MINECRAFT_LINK_PATH = '/v1/panel/account/minecraft-link';

export interface MinecraftLinkCode {
  code: string;
  command: string;
  expiresAt: number;
}

export function useCreateMinecraftLinkCode() {
  return useMutation({
    mutationFn: async (): Promise<MinecraftLinkCode> => {
      const response = await protoFetch(PanelMinecraftLinkCodeResponseSchema, MINECRAFT_LINK_PATH, { method: 'POST' });
      return {
        code: response.code,
        command: response.command,
        expiresAt: Date.now() + toNum(response.expiresInSeconds) * 1000,
      };
    },
  });
}

export function useUnlinkMinecraftAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (): Promise<void> => {
      const response = await apiFetch(MINECRAFT_LINK_PATH, { method: 'DELETE' });
      if (!response.ok) {
        throw await protoHttpErrorOf(response);
      }
    },
    onSuccess: () => {
      forgetMinecraftAccount(queryClient);
    },
  });
}

export function useConsumeLoginLink() {
  return useMutation({
    mutationFn: async (token: string) => protoSend(
      'POST',
      '/v1/panel/auth/link',
      PanelConsumeLoginLinkRequestSchema,
      create(PanelConsumeLoginLinkRequestSchema, { token }),
      PanelAuthResponseSchema,
    ),
  });
}

export interface StaffTwoFactorRequest {
  ip: string;
  requestedAt: string;
}

export function useStaffTwoFactorRequest(token: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['/v1/panel/account/two-factor/request', token],
    queryFn: async (): Promise<StaffTwoFactorRequest> => {
      const response = await protoSend(
        'POST',
        '/v1/panel/account/two-factor/request',
        Staff2faVerifyRequestSchema,
        create(Staff2faVerifyRequestSchema, { token: token ?? '' }),
        Staff2faPendingApprovalResponseSchema,
      );
      return { ip: response.ip, requestedAt: new Date(toNum(response.requestedAt)).toISOString() };
    },
    enabled: enabled && token !== undefined,
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}

export function useVerifyStaffTwoFactor() {
  return useMutation({
    mutationFn: async (token: string): Promise<void> => {
      await protoSend(
        'POST',
        '/v1/panel/account/two-factor/verify',
        Staff2faVerifyRequestSchema,
        create(Staff2faVerifyRequestSchema, { token }),
        Staff2faVerifyResponseSchema,
      );
    },
  });
}

const PANEL_SESSIONS_PATH = '/v1/panel/auth/sessions';
const PANEL_SESSIONS_QUERY_KEY = [PANEL_SESSIONS_PATH] as const;
const HTTP_NOT_FOUND = 404;

export interface PanelSession {
  id: string;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string | null;
  expiresAt: string | null;
  isCurrent: boolean;
}

function mapPanelSession(session: PanelSessionInfoResponse): PanelSession {
  return {
    id: session.id,
    ipAddress: session.ipAddress || null,
    userAgent: session.userAgent || null,
    createdAt: session.createdAt ?? null,
    expiresAt: session.expiresAt ?? null,
    isCurrent: session.isCurrent,
  };
}

export function usePanelSessions() {
  return useQuery({
    queryKey: PANEL_SESSIONS_QUERY_KEY,
    queryFn: async (): Promise<PanelSession[]> => {
      const response = await protoFetch(PanelSessionsResponseSchema, PANEL_SESSIONS_PATH);
      return response.sessions.map(mapPanelSession);
    },
    retry: false,
  });
}

export function useRevokePanelSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (sessionId: string): Promise<void> => {
      const response = await apiFetch(`${PANEL_SESSIONS_PATH}/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
      if (!response.ok && response.status !== HTTP_NOT_FOUND) {
        throw await protoHttpErrorOf(response);
      }
    },
    onSuccess: (_result, sessionId) => {
      queryClient.setQueryData<PanelSession[]>(PANEL_SESSIONS_QUERY_KEY, (sessions) =>
        sessions?.filter((session) => session.id !== sessionId));
    },
  });
}

export function useSendEmailChangeCode() {
  return useMutation({
    mutationFn: async (newEmail: string) => protoSend(
      'POST',
      '/v1/panel/auth/email/send-code',
      PanelUpdateEmailRequestSchema,
      create(PanelUpdateEmailRequestSchema, { newEmail }),
      PanelAuthResponseSchema,
    ),
  });
}

export interface EmailChangeConfirmation {
  newEmail: string;
  code: string;
  proof: CredentialProofInput;
}

export interface ProfileChanges {
  username?: string;
  language?: string;
  dateFormat?: string;
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (changes: ProfileChanges) => protoSend(
      'PATCH',
      '/v1/panel/auth/profile',
      PanelUpdateProfileRequestSchema,
      create(PanelUpdateProfileRequestSchema, changes),
      PanelProfileResponseSchema,
    ),
    onSuccess: () => {
      void invalidateSignedInProfile(queryClient);
    },
  });
}

export function useConfirmEmailChange() {
  return useMutation({
    mutationFn: async ({ newEmail, code, proof }: EmailChangeConfirmation) => protoSend(
      'PATCH',
      '/v1/panel/auth/email',
      PanelUpdateEmailWithCodeRequestSchema,
      create(PanelUpdateEmailWithCodeRequestSchema, { newEmail, code, proof }),
      PanelAuthResponseSchema,
    ),
  });
}
