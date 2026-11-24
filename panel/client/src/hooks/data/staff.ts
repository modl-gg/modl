import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { create, fromJsonString } from '@bufbuild/protobuf';
import { PanelAuthResponseSchema } from '@proto/modl/v1/auth_pb.ts';
import {
  AcceptInvitationRequestSchema,
  InviteResultResponseSchema,
  InviteStaffRequestSchema,
  type InviteResultResponse,
  PanelStaffListResponseSchema,
  PanelRoleListResponseSchema,
  PermissionsResponseSchema,
  RoleRequestSchema,
  RoleMutationResponseSchema,
  AssignMinecraftPlayerRequestSchema,
  StaffMutationResponseSchema,
  UpdateStaffRoleRequestSchema,
  ReorderRolesRequestSchema,
} from '@proto/modl/v1/staff_pb.ts';
import { ApiHttpError } from '@/lib/http-error';
import { protoFetch, protoSend, ProtoHttpError, READ_OPTS } from '@/lib/proto-fetch';
import { toNum } from '@/lib/proto-ui';
import { optionalField } from '@/lib/defined-fields';
import type { QueryGate } from './query-gate';
import { useInvalidatingMutation } from './invalidating-mutation';
import { invalidateSignedInProfile, isSignedInEmail } from './signed-in-profile';

export interface StaffMember {
  id: string;
  email: string;
  username: string;
  role: string;
  status: string;
  createdAt: string;
  assignedMinecraftUuid?: string;
  assignedMinecraftUsername?: string;
}

const PENDING_INVITATION_STATUS = 'Pending Invitation';

export function isPendingInvitation(member: StaffMember): boolean {
  return member.status === PENDING_INVITATION_STATUS;
}

export interface StaffRole {
  id: string;
  name: string;
  description: string;
  permissions: string[];
  isDefault: boolean;
  order: number;
  userCount: number;
}

export interface RolesResult {
  roles: StaffRole[];
}

export const STAFF_PERMISSION_CATEGORIES = ['punishment', 'player', 'ticket', 'admin', 'staff'] as const;

export type StaffPermissionCategory = (typeof STAFF_PERMISSION_CATEGORIES)[number];

function isStaffPermissionCategory(category: string): category is StaffPermissionCategory {
  return STAFF_PERMISSION_CATEGORIES.some((known) => known === category);
}

export interface StaffPermission {
  id: string;
  name: string;
  description: string;
  category: StaffPermissionCategory;
  parentId: string | null;
}

export interface PermissionsResult {
  permissions: StaffPermission[];
  categories: Record<string, string>;
}

const STAFF_QUERY_KEY = ['/v1/panel/staff'] as const;

export interface InviteLink {
  email: string;
  url: string;
}

export interface InviteStaffInput {
  email: string;
  role: string;
}

function toInviteLinks(result: InviteResultResponse): InviteLink[] {
  return result.inviteLinks.map(({ email, url }) => ({ email, url }));
}

function rejectedInviteReason(error: unknown): string | null {
  if (!(error instanceof ProtoHttpError)) return null;
  try {
    const result = fromJsonString(InviteResultResponseSchema, error.bodyText, READ_OPTS);
    return result.failed[0]?.reason || result.message || null;
  } catch {
    return null;
  }
}

async function readInviteResult(send: () => Promise<InviteResultResponse>): Promise<InviteLink[]> {
  try {
    return toInviteLinks(await send());
  } catch (error) {
    throw new Error(rejectedInviteReason(error) ?? '', { cause: error });
  }
}

export const INVITE_STAFF_MUTATION_KEY = ['/v1/panel/staff/invite'] as const;

export function useInviteStaff() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: INVITE_STAFF_MUTATION_KEY,
    mutationFn: async (input: InviteStaffInput) => readInviteResult(async () => protoSend(
      'POST',
      '/v1/panel/staff/invite',
      InviteStaffRequestSchema,
      create(InviteStaffRequestSchema, input),
      InviteResultResponseSchema,
    )),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: STAFF_QUERY_KEY }),
  });
}

export function useResendInvitation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (invitationId: string) => readInviteResult(async () => protoFetch(
      InviteResultResponseSchema,
      `/v1/panel/staff/invitations/${encodeURIComponent(invitationId)}/resend`,
      { method: 'POST' },
    )),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: STAFF_QUERY_KEY }),
  });
}

export type AcceptInvitationFailure = 'already-staff' | 'invalid-link' | 'unknown';

const HTTP_CONFLICT = 409;
const HTTP_TOO_MANY_REQUESTS = 429;

function isClientError(status: number): boolean {
  return status >= 400 && status < 500;
}

export function classifyAcceptInvitationFailure(error: unknown): AcceptInvitationFailure {
  if (!(error instanceof ApiHttpError)) return 'unknown';
  if (error.status === HTTP_CONFLICT) return 'already-staff';
  if (isClientError(error.status) && error.status !== HTTP_TOO_MANY_REQUESTS) return 'invalid-link';
  return 'unknown';
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: async ({ token, password }: { token: string; password?: string }) => protoSend(
      'POST',
      '/v1/public/staff/invitations/accept',
      AcceptInvitationRequestSchema,
      create(AcceptInvitationRequestSchema, { token, ...optionalField('password', password) }),
      PanelAuthResponseSchema,
    ),
  });
}

export function useStaff({ enabled = true }: QueryGate = {}) {
  return useQuery<StaffMember[]>({
    queryKey: STAFF_QUERY_KEY,
    enabled,
    queryFn: async () => {
      const res = await protoFetch(PanelStaffListResponseSchema, '/v1/panel/staff');
      return res.staff.map((member) => ({
        id: member.id,
        email: member.email,
        username: member.username,
        role: member.role,
        status: member.status,
        createdAt: new Date(toNum(member.createdAt)).toISOString(),
        ...optionalField('assignedMinecraftUuid', member.assignedMinecraftUuid || undefined),
        ...optionalField('assignedMinecraftUsername', member.assignedMinecraftUsername || undefined),
      }));
    },
    staleTime: 1000 * 60 * 5,
  });
}

export function useRoles({ enabled = true }: QueryGate = {}) {
  return useQuery<RolesResult>({
    queryKey: ['/v1/panel/roles'],
    enabled,
    queryFn: async () => {
      const res = await protoFetch(PanelRoleListResponseSchema, '/v1/panel/roles');
      return {
        roles: res.roles.map((role) => ({
          id: role.id,
          name: role.name,
          description: role.description,
          permissions: role.permissions,
          isDefault: role.isDefault,
          order: role.order,
          userCount: role.userCount,
        })),
      };
    },
  });
}

export function usePermissions() {
  return useQuery<PermissionsResult>({
    queryKey: ['/v1/panel/roles/permissions'],
    queryFn: async () => {
      const res = await protoFetch(PermissionsResponseSchema, '/v1/panel/roles/permissions');
      return {
        permissions: res.permissions.flatMap(({ id, name, description, category, parentId }) =>
          isStaffPermissionCategory(category) ? [{ id, name, description, category, parentId: parentId ?? null }] : [],
        ),
        categories: res.categories,
      };
    },
  });
}

export function useCreateRole() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (roleData: { name: string; description: string; permissions: string[] }) =>
      protoSend(
        'POST',
        '/v1/panel/roles',
        RoleRequestSchema,
        create(RoleRequestSchema, roleData),
        RoleMutationResponseSchema,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/roles'] });
    },
  });
}

export function useUpdateRole() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, ...roleData }: { id: string; name: string; description: string; permissions: string[] }) =>
      protoSend(
        'PUT',
        `/v1/panel/roles/${id}`,
        RoleRequestSchema,
        create(RoleRequestSchema, roleData),
        RoleMutationResponseSchema,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/roles'] });
    },
  });
}

export function useDeleteRole() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (roleId: string) =>
      protoFetch(RoleMutationResponseSchema, `/v1/panel/roles/${roleId}`, { method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['/v1/panel/roles'] });
    },
  });
}

export interface RoleOrderEntry {
  id: string;
  order: number;
}

export function useReorderRoles() {
  return useInvalidatingMutation(['/v1/panel/roles'], async (roleOrder: RoleOrderEntry[]) =>
    protoSend(
      'POST',
      '/v1/panel/roles/reorder',
      ReorderRolesRequestSchema,
      create(ReorderRolesRequestSchema, { roleOrder }),
      RoleMutationResponseSchema,
    ));
}

export function useAssignMinecraftPlayer() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      email,
      minecraftUuid,
      minecraftUsername
    }: {
      email: string;
      minecraftUuid?: string;
      minecraftUsername?: string;
    }) =>
      protoSend(
        'PATCH',
        `/v1/panel/staff/${encodeURIComponent(email)}/minecraft-player`,
        AssignMinecraftPlayerRequestSchema,
        create(AssignMinecraftPlayerRequestSchema, { minecraftUuid, minecraftUsername }),
        StaffMutationResponseSchema,
      ),
    onSuccess: (_response, { email }) => {
      void queryClient.invalidateQueries({ queryKey: STAFF_QUERY_KEY });
      if (isSignedInEmail(queryClient, email)) void invalidateSignedInProfile(queryClient);
    },
  });
}

export interface StaffRoleChange {
  staffId: string;
  role: string;
}

export function useChangeStaffRole() {
  return useInvalidatingMutation(STAFF_QUERY_KEY, async ({ staffId, role }: StaffRoleChange) =>
    protoSend(
      'PATCH',
      `/v1/panel/staff/${encodeURIComponent(staffId)}/role`,
      UpdateStaffRoleRequestSchema,
      create(UpdateStaffRoleRequestSchema, { role }),
      StaffMutationResponseSchema,
    ));
}

export function useRemoveStaffMember() {
  return useInvalidatingMutation(STAFF_QUERY_KEY, async (staffId: string) =>
    protoFetch(StaffMutationResponseSchema, `/v1/panel/staff/${encodeURIComponent(staffId)}`, { method: 'DELETE' }));
}
