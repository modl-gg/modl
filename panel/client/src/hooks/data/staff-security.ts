import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PasswordResetLinkResponseSchema, StaffSecurityListResponseSchema } from '@proto/modl/v1/sign_in_pb.ts';
import { apiFetch } from '@/lib/api';
import { protoFetch, protoHttpErrorOf } from '@/lib/proto-fetch';
import { SIGN_IN_SETTINGS_QUERY_KEY } from './sign-in';

const STAFF_SECURITY_PATH = '/v1/panel/staff-security';
const STAFF_SECURITY_QUERY_KEY = [STAFF_SECURITY_PATH] as const;

export interface StaffSecurityMember {
  staffId: string | null;
  email: string;
  hasPassword: boolean;
  hasSecondFactor: boolean;
  owner: boolean;
}

export interface PasswordResetLink {
  url: string;
  email: string;
}

export function useStaffSecurity() {
  return useQuery({
    queryKey: STAFF_SECURITY_QUERY_KEY,
    queryFn: async (): Promise<StaffSecurityMember[]> => {
      const response = await protoFetch(StaffSecurityListResponseSchema, STAFF_SECURITY_PATH);
      return response.entries.map((entry) => ({
        staffId: entry.staffId ?? null,
        email: entry.email,
        hasPassword: entry.hasPassword,
        hasSecondFactor: entry.totpEnabled || entry.hasPasskey,
        owner: entry.owner,
      }));
    },
  });
}

export function useCreatePasswordResetLink() {
  return useMutation({
    mutationFn: async (staffId: string): Promise<PasswordResetLink> => {
      const path = `${STAFF_SECURITY_PATH}/${encodeURIComponent(staffId)}/password-reset`;
      const response = await protoFetch(PasswordResetLinkResponseSchema, path, { method: 'POST' });
      return { url: response.url, email: response.email };
    },
  });
}

export function useResetSecondFactor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (staffId: string) => {
      const response = await apiFetch(`${STAFF_SECURITY_PATH}/${encodeURIComponent(staffId)}/second-factor`, { method: 'DELETE' });
      if (!response.ok) throw await protoHttpErrorOf(response);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: STAFF_SECURITY_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: SIGN_IN_SETTINGS_QUERY_KEY }),
      ]);
    },
  });
}
