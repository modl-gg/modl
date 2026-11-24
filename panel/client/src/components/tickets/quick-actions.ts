import type { QuickResponseAction, QuickResponseSettings } from '@proto/modl/v1/settings_pb.ts';
import type { TicketCategory } from './ticket-view';

export type AppealDecision = 'pardon' | 'reduce' | 'reject';

export const COMMENT_ACTION = 'Comment';
export const CLOSE_ACTION = 'Close';
export const REOPEN_ACTION = 'Reopen';

const REPORTED_PLAYER_PLACEHOLDER = '{reported-player}';

const QUICK_RESPONSE_TICKET_TYPES = {
  player_report: ['player_report', 'player'],
  chat_report: ['chat_report', 'chat'],
  bug_report: ['bug', 'bug_report'],
  appeal: ['appeal', 'punishment_appeal'],
  support: ['support'],
  application: ['application', 'staff'],
} as const satisfies Record<TicketCategory, readonly string[]>;

const APPEAL_DECISIONS: ReadonlyMap<string, AppealDecision> = new Map([
  ['pardon', 'pardon'],
  ['reduce', 'reduce'],
  ['reject', 'reject'],
]);

export function appealDecisionOf(action: QuickResponseAction | undefined): AppealDecision | undefined {
  return action === undefined ? undefined : APPEAL_DECISIONS.get(action.appealAction);
}

export function quickActionsFor(
  settings: QuickResponseSettings | null | undefined,
  category: TicketCategory,
  showsDecision: (decision: AppealDecision) => boolean,
): QuickResponseAction[] {
  const ticketTypes: readonly string[] = QUICK_RESPONSE_TICKET_TYPES[category];
  return (settings?.categories ?? [])
    .filter((quickCategory) => quickCategory.ticketTypes.some((type) => ticketTypes.includes(type.toLowerCase())))
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .flatMap((quickCategory) => quickCategory.actions)
    .filter((action) => {
      const decision = appealDecisionOf(action);
      return decision === undefined || showsDecision(decision);
    });
}

export function isReduceAction(actionName: string, action: QuickResponseAction | undefined): boolean {
  return appealDecisionOf(action) === 'reduce' || actionName.toLowerCase().includes('reduce');
}

export function fillQuickResponseMessage(message: string, reportedPlayer: string): string {
  return reportedPlayer ? message.replaceAll(REPORTED_PLAYER_PLACEHOLDER, reportedPlayer) : message;
}
