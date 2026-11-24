import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import type { QuickResponseAction, QuickResponseCategory } from '@/types/quickResponses';
import { newSettingsEntryId } from '@/lib/settings-ids';

type AppealAction = NonNullable<QuickResponseAction['appealAction']>;

const APPEAL_ACTIONS: readonly AppealAction[] = ['none', 'pardon', 'reduce', 'reject'];

const APPEAL_ACTION_LABEL_KEYS: Record<AppealAction, string> = {
  none: 'settings.tickets.appealNoAction',
  pardon: 'settings.tickets.appealPardon',
  reduce: 'settings.tickets.appealReduce',
  reject: 'settings.tickets.appealReject',
};

const QUICK_RESPONSE_TICKET_TYPES = ['player_report', 'chat_report', 'bug', 'appeal', 'support', 'application'];

const TICKET_TYPE_LABEL_KEYS: Readonly<Record<string, string>> = {
  chat_report: 'tickets.chatReport',
  player_report: 'tickets.playerReport',
  bug: 'tickets.bugReport',
  appeal: 'tickets.banAppeal',
  support: 'tickets.support',
  application: 'tickets.staffApplication',
};

export function ticketTypeLabel(type: string, t: TFunction): string {
  const key = TICKET_TYPE_LABEL_KEYS[type];
  if (key !== undefined) return t(key);
  return type
    .split('_')
    .map((word) => (word === '' ? word : word.charAt(0).toUpperCase() + word.slice(1)))
    .join(' ');
}

function isAppealAction(value: string): value is AppealAction {
  return APPEAL_ACTIONS.some((action) => action === value);
}

interface ActionDraft {
  name: string;
  message: string;
  closeTicket: boolean;
  showPunishment: boolean;
  appealAction: AppealAction;
}

function actionDraftOf(action: QuickResponseAction | null): ActionDraft {
  return {
    name: action?.name ?? '',
    message: action?.message ?? '',
    closeTicket: action?.closeTicket ?? false,
    showPunishment: action?.showPunishment ?? false,
    appealAction: action?.appealAction ?? 'none',
  };
}

interface QuickResponseActionDialogProps {
  action: QuickResponseAction | null;
  category: QuickResponseCategory;
  onSubmit: (action: QuickResponseAction) => void;
  onClose: () => void;
}

export function QuickResponseActionDialog({ action, category, onSubmit, onClose }: QuickResponseActionDialogProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<ActionDraft>(() => actionDraftOf(action));
  const isReportCategory = category.ticketTypes.some((type) => type.includes('report'));
  const isAppealCategory = category.ticketTypes.includes('appeal');
  const complete = draft.name !== '' && draft.message !== '';

  const submit = () => {
    if (!complete) return;
    onSubmit({
      id: action?.id ?? newSettingsEntryId('action'),
      order: action?.order ?? category.actions.length,
      ...draft,
    });
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-2xl" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{action ? t('settings.tickets.editQuickResponse') : t('settings.tickets.addQuickResponse')}</DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="action-name">{t('settings.tickets.actionName')}</Label>
              <Input
                id="action-name"
                value={draft.name}
                onChange={(event) => {
                  const name = event.target.value;
                  setDraft((current) => ({ ...current, name }));
                }}
                placeholder={t('settings.tickets.actionNamePlaceholder')}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="action-message">{t('settings.tickets.responseMessage')}</Label>
              <Textarea
                id="action-message"
                value={draft.message}
                onChange={(event) => {
                  const message = event.target.value;
                  setDraft((current) => ({ ...current, message }));
                }}
                placeholder={t('settings.tickets.responseMessagePlaceholder')}
                className="min-h-[100px]"
              />
            </div>

            <div className="flex items-center gap-2">
              <Switch
                id="close-ticket"
                checked={draft.closeTicket}
                onCheckedChange={(closeTicket) => {
                  setDraft((current) => ({ ...current, closeTicket }));
                }}
              />
              <Label htmlFor="close-ticket">{t('settings.tickets.closeTicketOnResponse')}</Label>
            </div>

            {isReportCategory && (
              <div className="flex items-center gap-2">
                <Switch
                  id="show-punishment"
                  checked={draft.showPunishment}
                  onCheckedChange={(showPunishment) => {
                    setDraft((current) => ({ ...current, showPunishment }));
                  }}
                />
                <Label htmlFor="show-punishment">{t('settings.tickets.showPunishmentInterface')}</Label>
              </div>
            )}

            {isAppealCategory && (
              <div className="space-y-2">
                <Label htmlFor="appeal-decision">{t('settings.tickets.appealDecision')}</Label>
                <Select
                  value={draft.appealAction}
                  onValueChange={(value) => {
                    if (isAppealAction(value)) setDraft((current) => ({ ...current, appealAction: value }));
                  }}
                >
                  <SelectTrigger id="appeal-decision" className="max-w-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {APPEAL_ACTIONS.map((appealAction) => (
                      <SelectItem key={appealAction} value={appealAction}>{t(APPEAL_ACTION_LABEL_KEYS[appealAction])}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {draft.appealAction === 'reduce' && (
                  <p className="text-sm text-muted-foreground">{t('settings.tickets.appealReduceDesc')}</p>
                )}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
            <Button onClick={submit} disabled={!complete}>
              {t('common.save')}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  );
}

interface QuickResponseCategoryDialogProps {
  category: QuickResponseCategory | null;
  order: number;
  onSubmit: (category: QuickResponseCategory) => void;
  onClose: () => void;
}

export function QuickResponseCategoryDialog({ category, order, onSubmit, onClose }: QuickResponseCategoryDialogProps) {
  const { t } = useTranslation();
  const [name, setName] = useState(category?.name ?? '');
  const [ticketTypes, setTicketTypes] = useState<string[]>(category?.ticketTypes ?? []);
  const complete = name !== '' && ticketTypes.length > 0;

  const submit = () => {
    if (!complete) return;
    onSubmit({
      id: category?.id ?? newSettingsEntryId('category'),
      name,
      ticketTypes,
      order: category?.order ?? order,
      actions: category?.actions ?? [],
    });
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{category ? t('settings.tickets.editCategory') : t('settings.tickets.addCategory')}</DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="category-name">{t('settings.tickets.categoryName')}</Label>
              <Input
                id="category-name"
                value={name}
                onChange={(event) => {
                  setName(event.target.value);
                }}
                placeholder={t('settings.tickets.categoryNamePlaceholder')}
              />
            </div>

            <div className="space-y-2">
              <span className="text-sm font-medium">{t('settings.tickets.ticketTypes')}</span>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {QUICK_RESPONSE_TICKET_TYPES.map((type) => (
                  <div key={type} className="flex items-center gap-2">
                    <Checkbox
                      id={`quick-response-type-${type}`}
                      checked={ticketTypes.includes(type)}
                      onCheckedChange={(state) => {
                        const checked = state === true;
                        setTicketTypes((current) => (checked ? [...current, type] : current.filter((candidate) => candidate !== type)));
                      }}
                    />
                    <Label htmlFor={`quick-response-type-${type}`} className="font-normal">{ticketTypeLabel(type, t)}</Label>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
            <Button onClick={submit} disabled={!complete}>
              {t('common.save')}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  );
}
