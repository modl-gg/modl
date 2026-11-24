import type { TFunction } from 'i18next';
import { CLOSE_ACTION } from './quick-actions';

export type ActionBadgeVariant = 'success' | 'secondary' | 'destructive' | 'outline';

export type ActionIconKind = 'accepted' | 'rejected' | 'pardoned' | 'reduced' | 'waiting' | 'investigating' | 'escalated' | 'closed' | 'generic';

export interface ActionBadge {
  text: string;
  variant: ActionBadgeVariant;
  icon: ActionIconKind;
}

interface ActionStyle {
  matches: readonly string[];
  variant: ActionBadgeVariant;
  icon: ActionIconKind;
}

const ACTION_STYLES: readonly ActionStyle[] = [
  { matches: ['pardon'], variant: 'success', icon: 'pardoned' },
  { matches: ['reject', 'denied'], variant: 'destructive', icon: 'rejected' },
  { matches: ['accept', 'fixed', 'completed', 'resolved'], variant: 'success', icon: 'accepted' },
  { matches: ['stale', 'pending', 'investigating'], variant: 'secondary', icon: 'waiting' },
  { matches: ['duplicate'], variant: 'secondary', icon: 'generic' },
  { matches: ['escalat'], variant: 'outline', icon: 'escalated' },
];

const REDUCED_DURATION_PATTERN = /reduced the punishment to (\d+) (\w+)/;

export function humanizeActionLabel(action: string): string {
  return action.includes('_')
    ? action
        .split('_')
        .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ')
    : action;
}

function reducedBadgeText(content: string, actionLabel: string, t: TFunction): string {
  const durationMatch = REDUCED_DURATION_PATTERN.exec(content);
  if (durationMatch) {
    return t('ticket.badge.reducedTo', { value: durationMatch[1] ?? '', unit: durationMatch[2] ?? '' });
  }
  return content.includes('permanent') ? t('ticket.badge.madePermanent') : actionLabel;
}

export function actionBadgeOf(action: string, content: string, t: TFunction): ActionBadge {
  const actionLabel = humanizeActionLabel(action);
  if (action === CLOSE_ACTION) {
    return { text: t('ticket.badge.closed'), variant: 'secondary', icon: 'closed' };
  }
  const actionLower = action.toLowerCase();
  if (actionLower.includes('reduce')) {
    return { text: reducedBadgeText(content, actionLabel, t), variant: 'outline', icon: 'reduced' };
  }
  const style = ACTION_STYLES.find(({ matches }) => matches.some((match) => actionLower.includes(match)));
  return { text: actionLabel, variant: style?.variant ?? 'outline', icon: style?.icon ?? 'generic' };
}

const QUICK_ACTION_ICONS: readonly { matches: readonly string[]; icon: ActionIconKind }[] = [
  { matches: ['accept', 'completed', 'fixed'], icon: 'accepted' },
  { matches: ['reject', 'duplicate'], icon: 'rejected' },
  { matches: ['pardon'], icon: 'pardoned' },
  { matches: ['reduce'], icon: 'reduced' },
  { matches: ['stale', 'pending'], icon: 'waiting' },
  { matches: ['investigating', 'info'], icon: 'investigating' },
  { matches: ['escalat'], icon: 'escalated' },
];

export function quickActionIconOf(actionName: string): ActionIconKind {
  const name = actionName.toLowerCase();
  return QUICK_ACTION_ICONS.find(({ matches }) => matches.some((match) => name.includes(match)))?.icon ?? 'generic';
}
