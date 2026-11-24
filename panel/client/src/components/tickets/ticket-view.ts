import { isClosedTicketStatus } from '@/lib/ticket-enums';
import type { PanelTicket, PanelTicketReply, TicketAttachment } from '@/hooks/data/tickets';

export type TicketCategory = 'player_report' | 'chat_report' | 'bug_report' | 'appeal' | 'support' | 'application';

export type TicketSenderType = 'user' | 'staff' | 'system';

export type TicketOpenState = 'Open' | 'Closed';

export const TICKET_CATEGORY_LABEL_KEYS = {
  player_report: 'tickets.playerReport',
  chat_report: 'tickets.chatReport',
  bug_report: 'tickets.bugReport',
  appeal: 'ticket.category.appeal',
  support: 'tickets.support',
  application: 'ticket.category.application',
} as const satisfies Record<TicketCategory, string>;

const CATEGORY_BY_TYPE: ReadonlyMap<string, TicketCategory> = new Map([
  ['bug', 'bug_report'],
  ['chat', 'chat_report'],
  ['player', 'player_report'],
  ['appeal', 'appeal'],
  ['support', 'support'],
  ['application', 'application'],
  ['staff', 'application'],
]);

const TYPE_BY_ID_PREFIX: readonly (readonly [string, string])[] = [
  ['BUG', 'bug'],
  ['PLAYER', 'player'],
  ['APPEAL', 'appeal'],
];

const NON_CLOSING_ACTIONS: ReadonlySet<string> = new Set(['', 'Comment', 'Reopen']);

export interface TicketMessageView {
  id: string;
  sender: string;
  senderType: TicketSenderType;
  content: string;
  created: number;
  staff: boolean;
  avatar: string;
  attachments: TicketAttachment[];
  closedAs: string | undefined;
  creatorIdentifier: string;
}

function inferredTicketType(ticket: PanelTicket): string {
  if (ticket.type) return ticket.type.toLowerCase();
  return TYPE_BY_ID_PREFIX.find(([prefix]) => ticket.id.startsWith(prefix))?.[1] ?? 'support';
}

export function ticketCategoryOf(ticket: PanelTicket): TicketCategory {
  return CATEGORY_BY_TYPE.get(inferredTicketType(ticket)) ?? 'support';
}

export function isReportCategory(category: TicketCategory): boolean {
  return category === 'player_report' || category === 'chat_report';
}

export function ticketOpenStateOf(ticket: PanelTicket): TicketOpenState {
  return ticket.locked || isClosedTicketStatus(ticket.status) ? 'Closed' : 'Open';
}

function senderTypeOf(reply: PanelTicketReply): TicketSenderType {
  if (reply.type === 'staff') return 'staff';
  if (reply.type === 'system') return 'system';
  return 'user';
}

function senderNameOf(reply: PanelTicketReply, index: number, ticket: PanelTicket): string {
  if (reply.name || reply.staff || index > 0) return reply.name;
  return ticket.creatorName || ticket.reportedBy;
}

export function closingActionOf(action: string): string | undefined {
  return NON_CLOSING_ACTIONS.has(action) ? undefined : action;
}

export function ticketMessagesOf(ticket: PanelTicket): TicketMessageView[] {
  return ticket.messages.map((reply, index) => ({
    id: reply.id,
    sender: senderNameOf(reply, index, ticket),
    senderType: senderTypeOf(reply),
    content: reply.content,
    created: reply.created,
    staff: reply.staff,
    avatar: reply.avatar,
    attachments: reply.attachments,
    closedAs: closingActionOf(reply.action),
    creatorIdentifier: reply.creatorIdentifier,
  }));
}

export function isStaffMessage(message: TicketMessageView): boolean {
  return message.senderType === 'staff' || message.staff;
}

export function originalCreatorIdentifierOf(messages: readonly TicketMessageView[]): string | undefined {
  return messages.find((message) => message.senderType === 'user' && message.creatorIdentifier !== '')?.creatorIdentifier;
}

export function messageIdentityKey(message: TicketMessageView): string {
  return message.id || `${String(message.created)}|${message.sender}`;
}

const URL_SAFE_ID_PREFIX = 'ID-';
const TICKET_ID_PREFIX = '#';

export function ticketIdOfRouteSegment(segment: string): string {
  return segment.startsWith(URL_SAFE_ID_PREFIX) ? segment.replace(URL_SAFE_ID_PREFIX, TICKET_ID_PREFIX) : segment;
}

export function ticketRoutePath(ticketId: string): string {
  return `/panel/tickets/${encodeURIComponent(ticketId.replace(TICKET_ID_PREFIX, URL_SAFE_ID_PREFIX))}`;
}

