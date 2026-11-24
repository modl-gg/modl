import { useEffect, useRef } from 'react';
import type { QueryClient} from '@tanstack/react-query';
import { useQueryClient } from '@tanstack/react-query';
import { create, fromBinary, toBinary } from '@bufbuild/protobuf';
import {
  AckKind,
  AckSchema,
  ClientHelloSchema,
  ClientKind,
  ErrorCode,
  HeartbeatSchema,
  PanelResource,
  PanelResourceSchema,
  RealtimeCloseCode,
  type RealtimeEnvelope,
  RealtimeEnvelopeSchema,
  ReconnectAction,
  ReconnectReason,
  Topic,
} from '@proto/modl/v1/realtime_pb.ts';
import { useAuth } from '@/hooks/use-auth';
import { SIGNED_IN_PROFILE_QUERY_KEY } from '@/hooks/data/signed-in-profile';
import { AUDIT_QUERY_ROOTS } from '@/hooks/data/audit-query-keys';
import { readSessionKey } from '@/lib/panel-session';
import { isSettingsQueryKey } from '@/lib/settings-queries';
import { createCoalescingQueue, type CoalescingQueue, type CoalescingTimers } from '@/lib/coalescing-queue';

const REALTIME_PROTOCOL_VERSION = 1;
const HEARTBEAT_INTERVAL_MS = 30_000;
const INITIAL_RECONNECT_DELAY_MS = 1_000;
const MAX_RECONNECT_DELAY_MS = 30_000;
const MAX_ADVISED_RECONNECT_DELAY_MS = 60_000;
const RECENT_EVENT_ID_LIMIT = 256;
const REALTIME_PATH = '/v1/realtime/ws';
const INVALIDATION_WINDOW = { quietMs: 500, maxWaitMs: 2_000 } as const;
const SESSION_STOPPING_CLOSE_CODES: ReadonlySet<number> = new Set<number>([
  RealtimeCloseCode.AUTHENTICATION_FAILED,
  RealtimeCloseCode.SESSION_ENDED,
  RealtimeCloseCode.UNSUPPORTED_PROTOCOL,
  RealtimeCloseCode.PROTOCOL_VIOLATION,
  RealtimeCloseCode.DISABLED,
]);

const PANEL_TOPICS = [
  Topic.PANEL_TICKETS,
  Topic.PANEL_ASSIGNED_TICKETS,
  Topic.PANEL_PLAYERS,
  Topic.PANEL_PUNISHMENTS,
  Topic.PANEL_STAFF,
  Topic.PANEL_ROLES,
  Topic.PANEL_SETTINGS,
  Topic.PANEL_PUNISHMENT_TYPES,
  Topic.PANEL_KNOWLEDGEBASE,
  Topic.PANEL_AUDIT,
  Topic.PANEL_HOMEPAGE,
  Topic.PANEL_APPEALS,
  Topic.PANEL_DASHBOARD,
  Topic.PANEL_NOTIFICATIONS,
] as const;
const PANEL_TOPIC_SET = new Set<Topic>(PANEL_TOPICS);

type InvalidateQueries = Pick<QueryClient, 'invalidateQueries'>;
type RealtimePayload = RealtimeEnvelope['payload'];
type InvalidationCase = 'ticketChanged' | 'assignedTicketSubscriptionChanged' | 'panelInvalidated';
export type InvalidationPayload = Extract<RealtimePayload, { case: InvalidationCase }>;
interface QueryScope {
  readonly scope: 'settings';
}

type InvalidationTarget = readonly unknown[] | QueryScope;

const SETTINGS_QUERIES: QueryScope = { scope: 'settings' };

const STAFF_TARGETS: InvalidationTarget[] = [
  SIGNED_IN_PROFILE_QUERY_KEY,
  ['/v1/panel/staff'],
  ['/v1/panel/roles'],
  ['/v1/panel/roles/permissions'],
  ['userPermissions'],
];

const ALL_SETTINGS_TARGETS: InvalidationTarget[] = [SETTINGS_QUERIES];

const DASHBOARD_METRICS_TARGET: InvalidationTarget = ['/v1/panel/dashboard/metrics'];
const DASHBOARD_RECENT_TICKETS_TARGET: InvalidationTarget = ['/v1/panel/dashboard/recent-tickets'];
const DASHBOARD_RECENT_PUNISHMENTS_TARGET: InvalidationTarget = ['/v1/panel/dashboard/recent-punishments'];

const DASHBOARD_TARGETS: InvalidationTarget[] = [
  DASHBOARD_METRICS_TARGET,
  DASHBOARD_RECENT_TICKETS_TARGET,
  DASHBOARD_RECENT_PUNISHMENTS_TARGET,
];

const TICKET_DASHBOARD_TARGETS: InvalidationTarget[] = [
  DASHBOARD_METRICS_TARGET,
  DASHBOARD_RECENT_TICKETS_TARGET,
];

const PUNISHMENT_DASHBOARD_TARGETS: InvalidationTarget[] = [
  DASHBOARD_METRICS_TARGET,
  DASHBOARD_RECENT_PUNISHMENTS_TARGET,
];

const AUDIT_TARGETS: InvalidationTarget[] = [...AUDIT_QUERY_ROOTS];

const NOTIFICATION_TARGETS: InvalidationTarget[] = [
  ['/v1/panel/ticket-subscriptions/updates'],
  ['/v1/panel/ticket-subscriptions/assigned-updates'],
];

export function isPanelRealtimeEnabled(): boolean {
  return import.meta.env.VITE_REALTIME_ENABLED !== 'false';
}

export function createRealtimeWebSocketUrl(location: Location): string {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${location.host}${REALTIME_PATH}`;
}

export function getReconnectDelayMs(attempt: number, random: () => number = Math.random): number {
  const cappedAttempt = Math.min(attempt, 5);
  const baseDelay = Math.min(MAX_RECONNECT_DELAY_MS, INITIAL_RECONNECT_DELAY_MS * 2 ** cappedAttempt);
  return baseDelay + Math.floor(random() * Math.min(baseDelay, 1_000));
}

export function getAdvisedReconnectDelayMs(retryAfterMs: number, random: () => number = Math.random): number {
  const cappedDelay = Math.max(0, Math.min(retryAfterMs, MAX_ADVISED_RECONNECT_DELAY_MS));
  return cappedDelay + Math.floor(random() * Math.min(Math.max(cappedDelay, 1), 1_000));
}

function knownPanelResource(resource: PanelResource): PanelResource {
  return resource in PanelResourceSchema.value ? resource : PanelResource.UNSPECIFIED;
}

function getTopicForPanelResource(resource: PanelResource): Topic | null {
  switch (knownPanelResource(resource)) {
    case PanelResource.PLAYERS:
      return Topic.PANEL_PLAYERS;
    case PanelResource.PUNISHMENTS:
      return Topic.PANEL_PUNISHMENTS;
    case PanelResource.STAFF:
      return Topic.PANEL_STAFF;
    case PanelResource.ROLES:
      return Topic.PANEL_ROLES;
    case PanelResource.SETTINGS:
      return Topic.PANEL_SETTINGS;
    case PanelResource.PUNISHMENT_TYPES:
      return Topic.PANEL_PUNISHMENT_TYPES;
    case PanelResource.KNOWLEDGEBASE:
      return Topic.PANEL_KNOWLEDGEBASE;
    case PanelResource.AUDIT:
      return Topic.PANEL_AUDIT;
    case PanelResource.HOMEPAGE:
      return Topic.PANEL_HOMEPAGE;
    case PanelResource.APPEALS:
      return Topic.PANEL_APPEALS;
    case PanelResource.TICKETS:
      return Topic.PANEL_TICKETS;
    case PanelResource.DASHBOARD:
      return Topic.PANEL_DASHBOARD;
    case PanelResource.NOTIFICATIONS:
      return Topic.PANEL_NOTIFICATIONS;
    case PanelResource.UNSPECIFIED:
      return null;
  }
}

export function getRequiredTopicForRealtimePayload(payload: InvalidationPayload): Topic | null {
  switch (payload.case) {
    case 'ticketChanged':
      return Topic.PANEL_TICKETS;
    case 'assignedTicketSubscriptionChanged':
      return Topic.PANEL_ASSIGNED_TICKETS;
    case 'panelInvalidated':
      return getTopicForPanelResource(payload.value.resource);
  }
}

function getTicketInvalidationTargets(ticketId?: string): InvalidationTarget[] {
  return [
    ['/v1/panel/tickets'],
    ['/v1/panel/tickets/counts'],
    ...(ticketId ? [['/v1/panel/tickets', ticketId] as const] : []),
    ...TICKET_DASHBOARD_TARGETS,
  ];
}

function getPanelResourceInvalidationTargets(resource: PanelResource, resourceId?: string): InvalidationTarget[] {
  const id = resourceId?.trim();
  switch (knownPanelResource(resource)) {
    case PanelResource.PLAYERS:
      return id
        ? [
            ['/v1/panel/players', id],
            ['/v1/panel/players/linked', id],
            ['/v1/panel/players', id, 'replays'],
          ]
        : [['/v1/panel/players']];
    case PanelResource.PUNISHMENTS:
      return [
        ...(id
          ? [['/v1/panel/players/punishments', id, 'linked-bans'] as const]
          : [['/v1/panel/players'] as const]),
        ...PUNISHMENT_DASHBOARD_TARGETS,
      ];
    case PanelResource.STAFF:
    case PanelResource.ROLES:
      return STAFF_TARGETS;
    case PanelResource.SETTINGS:
    case PanelResource.PUNISHMENT_TYPES:
      return ALL_SETTINGS_TARGETS;
    case PanelResource.KNOWLEDGEBASE:
      return [['knowledgebaseCategories'], ['homepageCards']];
    case PanelResource.HOMEPAGE:
      return [['homepageCards']];
    case PanelResource.AUDIT:
      return AUDIT_TARGETS;
    case PanelResource.APPEALS:
      return [
        ['/v1/panel/appeals'],
        ...(id ? [['/v1/panel/appeals/punishment', id] as const] : []),
      ];
    case PanelResource.TICKETS:
      return getTicketInvalidationTargets(id);
    case PanelResource.DASHBOARD:
      return DASHBOARD_TARGETS;
    case PanelResource.NOTIFICATIONS:
      return NOTIFICATION_TARGETS;
    case PanelResource.UNSPECIFIED:
      return [];
  }
}

export function getRealtimeInvalidationTargets(payload: InvalidationPayload): InvalidationTarget[] {
  switch (payload.case) {
    case 'ticketChanged':
      return getTicketInvalidationTargets(payload.value.ticketId.trim() || undefined);
    case 'assignedTicketSubscriptionChanged':
      return [
        ['/v1/panel/ticket-subscriptions/updates'],
        ['/v1/panel/ticket-subscriptions/assigned-updates'],
      ];
    case 'panelInvalidated':
      return getPanelResourceInvalidationTargets(payload.value.resource, payload.value.resourceId);
  }
}

function getTopicInvalidationTargets(topic: Topic): InvalidationTarget[] {
  switch (topic) {
    case Topic.PANEL_TICKETS:
      return getTicketInvalidationTargets();
    case Topic.PANEL_ASSIGNED_TICKETS:
      return [
        ['/v1/panel/ticket-subscriptions/updates'],
        ['/v1/panel/ticket-subscriptions/assigned-updates'],
      ];
    case Topic.PANEL_PLAYERS:
      return [['/v1/panel/players']];
    case Topic.PANEL_PUNISHMENTS:
      return [['/v1/panel/players'], ...PUNISHMENT_DASHBOARD_TARGETS];
    case Topic.PANEL_STAFF:
    case Topic.PANEL_ROLES:
      return STAFF_TARGETS;
    case Topic.PANEL_SETTINGS:
    case Topic.PANEL_PUNISHMENT_TYPES:
      return ALL_SETTINGS_TARGETS;
    case Topic.PANEL_KNOWLEDGEBASE:
      return [['knowledgebaseCategories'], ['homepageCards']];
    case Topic.PANEL_HOMEPAGE:
      return [['homepageCards']];
    case Topic.PANEL_AUDIT:
      return AUDIT_TARGETS;
    case Topic.PANEL_APPEALS:
      return [['/v1/panel/appeals']];
    case Topic.PANEL_DASHBOARD:
      return DASHBOARD_TARGETS;
    case Topic.PANEL_NOTIFICATIONS:
      return NOTIFICATION_TARGETS;
    case Topic.UNSPECIFIED:
    case Topic.MINECRAFT_PUNISHMENTS:
    case Topic.MINECRAFT_PERMISSIONS:
    case Topic.MINECRAFT_PUNISHMENT_TYPES:
    case Topic.MINECRAFT_STAFF_NOTIFICATIONS:
    case Topic.MINECRAFT_PRESENCE:
    case Topic.MINECRAFT_PLAYER_NOTIFICATIONS:
    case Topic.MINECRAFT_STAFF_2FA:
    case Topic.MINECRAFT_STAT_WIPES:
    case Topic.NETWORK_MODERATION:
    case Topic.NETWORK_MEMBER_ACTIONS:
    case Topic.NETWORK_REPLAY_CAPTURE:
    case Topic.NETWORK_ROSTER:
      return [];
  }
}

function getSubscribedRealtimeInvalidationTargets(topics: readonly Topic[]): InvalidationTarget[] {
  const seen = new Set<string>();
  const targets: InvalidationTarget[] = [];

  for (const topic of topics) {
    if (!PANEL_TOPIC_SET.has(topic)) continue;

    for (const target of getTopicInvalidationTargets(topic)) {
      const key = JSON.stringify(target);
      if (seen.has(key)) continue;
      seen.add(key);
      targets.push(target);
    }
  }

  return targets;
}

function isQueryScope(target: InvalidationTarget): target is QueryScope {
  return !Array.isArray(target);
}

export function invalidateRealtimeTargets(queryClient: InvalidateQueries, targets: InvalidationTarget[]): void {
  for (const target of targets) {
    if (isQueryScope(target)) {
      void queryClient.invalidateQueries({ predicate: (query) => isSettingsQueryKey(query.queryKey) });
    } else {
      void queryClient.invalidateQueries({ queryKey: target });
    }
  }
}

const WINDOW_TIMERS: CoalescingTimers = {
  now: () => Date.now(),
  schedule: (run, delayMs) => window.setTimeout(run, delayMs),
  cancel: (handle) => {
    if (typeof handle === 'number') window.clearTimeout(handle);
  },
};

function createInvalidationQueue(queryClient: InvalidateQueries): CoalescingQueue<InvalidationTarget> {
  return createCoalescingQueue<InvalidationTarget>(
    (target) => JSON.stringify(target),
    (targets) => { invalidateRealtimeTargets(queryClient, targets); },
    INVALIDATION_WINDOW,
    WINDOW_TIMERS,
  );
}

export function resyncCacheAfterConnectionLoss(queryClient: InvalidateQueries): void {
  void queryClient.invalidateQueries();
}

function buildEnvelope(payload: RealtimeEnvelope['payload']): RealtimeEnvelope {
  return create(RealtimeEnvelopeSchema, {
    protocolVersion: REALTIME_PROTOCOL_VERSION,
    payload,
  });
}

function buildClientHello(): RealtimeEnvelope {
  return buildEnvelope({
    case: 'clientHello',
    value: create(ClientHelloSchema, {
      clientKind: ClientKind.PANEL,
      protocolVersion: REALTIME_PROTOCOL_VERSION,
      supportedTopics: [...PANEL_TOPICS],
      sessionKey: readSessionKey() ?? undefined,
    }),
  });
}

function buildHeartbeat(sequence: bigint): RealtimeEnvelope {
  return buildEnvelope({
    case: 'heartbeat',
    value: create(HeartbeatSchema, { sequence }),
  });
}

function buildTransportAck(eventId: string): RealtimeEnvelope {
  return buildEnvelope({
    case: 'ack',
    value: create(AckSchema, {
      kind: AckKind.TRANSPORT,
      eventId,
    }),
  });
}

function sendEnvelope(socket: WebSocket | null, envelope: RealtimeEnvelope): void {
  if (socket?.readyState !== WebSocket.OPEN) return;
  socket.send(toBinary(RealtimeEnvelopeSchema, envelope));
}

function shouldStopForError(envelope: RealtimeEnvelope): boolean {
  if (envelope.payload.case !== 'error') return false;
  return envelope.payload.value.code === ErrorCode.UNAUTHORIZED
    || envelope.payload.value.code === ErrorCode.FORBIDDEN
    || envelope.payload.value.code === ErrorCode.UNSUPPORTED_PROTOCOL;
}

function shouldStopForReconnectAdvice(envelope: RealtimeEnvelope): boolean {
  if (envelope.payload.case !== 'reconnectAdvice') return false;
  const advice = envelope.payload.value;
  return advice.action === ReconnectAction.STOP
    || advice.action === ReconnectAction.REAUTHENTICATE
    || advice.reason === ReconnectReason.AUTH_EXPIRED
    || advice.reason === ReconnectReason.PROTOCOL_UPGRADE;
}

async function readFrameBytes(data: unknown): Promise<Uint8Array | null> {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (data instanceof Blob) return new Uint8Array(await data.arrayBuffer());
  return null;
}

function markEventId(eventIds: string[], eventId: string): boolean {
  if (!eventId) return true;
  if (eventIds.includes(eventId)) return false;
  eventIds.push(eventId);
  if (eventIds.length > RECENT_EVENT_ID_LIMIT) {
    eventIds.splice(0, eventIds.length - RECENT_EVENT_ID_LIMIT);
  }
  return true;
}

export function useRealtimeInvalidation(): void {
  const queryClient = useQueryClient();
  const { user, isLoading } = useAuth();
  const authKey = user?.secondFactor === 'satisfied' ? user.id : null;
  const stoppedAuthKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (authKey === null) {
      stoppedAuthKeyRef.current = null;
    }
  }, [authKey]);

  useEffect(() => {
    if (!isPanelRealtimeEnabled() || isLoading || authKey === null || typeof window === 'undefined') return;
    if (stoppedAuthKeyRef.current === authKey) return;

    let active = true;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | undefined;
    let heartbeatTimer: number | undefined;
    let reconnectAttempt = 0;
    let heartbeatSequence = BigInt(0);
    let connectedOnce = false;
    let subscribedTopics: Topic[] = [];
    let reconnectDelayOverrideMs: number | undefined;
    const recentEventIds: string[] = [];
    const invalidations = createInvalidationQueue(queryClient);

    const cleanupTimers = () => {
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
        reconnectTimer = undefined;
      }
      if (heartbeatTimer !== undefined) {
        window.clearInterval(heartbeatTimer);
        heartbeatTimer = undefined;
      }
    };

    const stopForAuthSession = () => {
      stoppedAuthKeyRef.current = authKey;
      active = false;
      invalidations.dispose();
      cleanupTimers();
      socket?.close();
      socket = null;
    };

    const scheduleReconnect = () => {
      if (!active || reconnectTimer !== undefined) return;
      const delayMs = reconnectDelayOverrideMs !== undefined
        ? getAdvisedReconnectDelayMs(reconnectDelayOverrideMs)
        : getReconnectDelayMs(reconnectAttempt++);
      reconnectDelayOverrideMs = undefined;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = undefined;
        connect();
      }, delayMs);
    };

    const startHeartbeat = () => {
      if (heartbeatTimer !== undefined) {
        window.clearInterval(heartbeatTimer);
      }
      heartbeatTimer = window.setInterval(() => {
        heartbeatSequence += BigInt(1);
        sendEnvelope(socket, buildHeartbeat(heartbeatSequence));
      }, HEARTBEAT_INTERVAL_MS);
    };

    const handleEnvelope = (envelope: RealtimeEnvelope) => {
      if (envelope.eventId) {
        sendEnvelope(socket, buildTransportAck(envelope.eventId));
      }

      if (shouldStopForError(envelope) || shouldStopForReconnectAdvice(envelope)) {
        stopForAuthSession();
        return;
      }

      const { payload } = envelope;
      switch (payload.case) {
        case 'serverHello':
          reconnectAttempt = 0;
          subscribedTopics = payload.value.acceptedTopics.filter((topic) => PANEL_TOPIC_SET.has(topic));
          if (connectedOnce) {
            invalidations.discard();
            resyncCacheAfterConnectionLoss(queryClient);
          }
          connectedOnce = true;
          break;
        case 'reconnectAdvice':
          if (payload.value.action === ReconnectAction.RECONNECT && payload.value.retryAfterMs !== undefined) {
            reconnectDelayOverrideMs = payload.value.retryAfterMs;
          }
          if (payload.value.action === ReconnectAction.RESYNC) {
            invalidations.add(getSubscribedRealtimeInvalidationTargets(subscribedTopics));
          }
          break;
        case 'ticketChanged':
        case 'assignedTicketSubscriptionChanged':
        case 'panelInvalidated': {
          const requiredTopic = getRequiredTopicForRealtimePayload(payload);
          if (requiredTopic !== null && subscribedTopics.includes(requiredTopic) && markEventId(recentEventIds, envelope.eventId)) {
            invalidations.add(getRealtimeInvalidationTargets(payload));
          }
          break;
        }
        case 'heartbeat':
        case 'clientHello':
        case 'ack':
        case 'error':
        case 'subscribe':
        case 'unsubscribe':
        case 'permissionInvalidated':
        case 'punishmentTypeInvalidated':
        case 'staffNotification':
        case 'presenceSnapshot':
        case 'presenceDelta':
        case 'presenceInvalidated':
        case 'punishmentNotification':
        case 'playerNotification':
        case 'staffTwoFactor':
        case 'punishmentPush':
        case 'playerNotificationPush':
        case 'staffNotificationPush':
        case 'statWipePush':
        case 'staff2faPush':
        case 'activeStaffPush':
        case 'syncRequested':
        case 'moderationState':
        case 'memberAction':
        case 'memberActionResult':
        case 'replayCaptureRequest':
        case 'replayCaptureResult':
        case 'hostedPlayers':
        case 'networkRoster':
        case undefined:
          break;
      }
    };

    const receiveFrame = async (frameSocket: WebSocket, data: unknown) => {
      try {
        const bytes = await readFrameBytes(data);
        if (bytes === null) {
          frameSocket.close(1003, 'Invalid realtime frame');
          return;
        }
        handleEnvelope(fromBinary(RealtimeEnvelopeSchema, bytes));
      } catch {
        frameSocket.close(1003, 'Invalid realtime frame');
      }
    };

    const connect = () => {
      if (!active) return;
      const nextSocket = new WebSocket(createRealtimeWebSocketUrl(window.location));
      socket = nextSocket;
      nextSocket.binaryType = 'arraybuffer';

      nextSocket.onopen = () => {
        sendEnvelope(nextSocket, buildClientHello());
        startHeartbeat();
      };

      nextSocket.onmessage = (event: MessageEvent<unknown>) => {
        void receiveFrame(nextSocket, event.data);
      };

      nextSocket.onclose = (event) => {
        if (socket === nextSocket) socket = null;
        if (heartbeatTimer !== undefined) {
          window.clearInterval(heartbeatTimer);
          heartbeatTimer = undefined;
        }
        if (SESSION_STOPPING_CLOSE_CODES.has(event.code)) {
          stopForAuthSession();
          return;
        }
        scheduleReconnect();
      };

      nextSocket.onerror = () => {
        nextSocket.close();
      };
    };

    connect();

    return () => {
      active = false;
      invalidations.flush();
      invalidations.dispose();
      cleanupTimers();
      socket?.close();
      socket = null;
    };
  }, [authKey, isLoading, queryClient]);
}

export function RealtimeProvider(): null {
  useRealtimeInvalidation();
  return null;
}
