import { useId, useMemo, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { ListChecks, Loader2 } from 'lucide-react';
import { Button, buttonVariants } from '@/components/ui/button';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import { useAuditPunishmentTypes, useBulkPunishmentAction, type AuditPunishmentType, type BulkPunishmentAction } from '@/hooks/data/audit';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { DAY_MS, HOUR_MS, MAX_REASON_LENGTH, MINUTE_MS, pickOption } from './audit-format';

type DurationUnit = 'minutes' | 'hours' | 'days';
type BulkOperation = BulkPunishmentAction['kind'];

const DURATION_UNITS: readonly DurationUnit[] = ['minutes', 'hours', 'days'];
const DURATION_UNIT_MS: Readonly<Record<DurationUnit, number>> = {
  minutes: MINUTE_MS,
  hours: HOUR_MS,
  days: DAY_MS,
};
const BULK_OPERATIONS: readonly BulkOperation[] = ['pardon', 'set-expiration'];
const NO_TYPES: readonly AuditPunishmentType[] = [];

export function BulkPunishmentActionsModal() {
  const { t } = useTranslation();
  const { toast } = useToast();
  const typesId = useId();
  const reasonId = useId();
  const bulkAction = useBulkPunishmentAction();
  const [operation, setOperation] = useState<BulkOperation>('pardon');
  const [selectedTypes, setSelectedTypes] = useState<ReadonlySet<number>>(() => new Set());
  const [reason, setReason] = useState('');
  const [durationValue, setDurationValue] = useState<number>(1);
  const [durationUnit, setDurationUnit] = useState<DurationUnit>('days');
  const [isPermanent, setIsPermanent] = useState(false);
  const [open, setOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const typesQuery = useAuditPunishmentTypes({ enabled: open });
  const isComplete = typesQuery.data !== undefined;
  const isError = typesQuery.isError && !isComplete;
  const activeTypes = useMemo(
    () => (typesQuery.data ?? NO_TYPES).filter((type) => type.activeCount > 0),
    [typesQuery.data],
  );
  const previewCount = activeTypes
    .filter((type) => selectedTypes.has(type.ordinal))
    .reduce((sum, type) => sum + type.activeCount, 0);

  const toggleType = (ordinal: number) => {
    setSelectedTypes(prev => {
      const next = new Set(prev);
      if (next.has(ordinal)) {
        next.delete(ordinal);
      } else {
        next.add(ordinal);
      }
      return next;
    });
  };

  const buildAction = (): BulkPunishmentAction => {
    const typeOrdinals = Array.from(selectedTypes);
    const trimmedReason = reason.trim();
    switch (operation) {
      case 'pardon':
        return { kind: 'pardon', typeOrdinals, reason: trimmedReason };
      case 'set-expiration':
        return {
          kind: 'set-expiration',
          typeOrdinals,
          reason: trimmedReason,
          newDurationMs: isPermanent ? 0 : durationValue * DURATION_UNIT_MS[durationUnit],
        };
    }
  };

  const performSubmit = () => {
    setConfirmOpen(false);
    const action = buildAction();
    bulkAction.mutate(action, {
      onSuccess: (count) => {
        toast({
          title: action.kind === 'pardon' ? t('audit.bulk.pardonComplete') : t('audit.bulk.expirationUpdated'),
          description: t('audit.bulk.affected', { count }),
        });
        setOpen(false);
        setSelectedTypes(new Set());
        setReason('');
      },
      onError: (error) => {
        toast({
          title: t('audit.bulk.failed'),
          description: describeError(error, t('common.unknownError')),
          variant: 'destructive',
        });
      },
    });
  };

  const handleDurationValueChange = (value: string) => {
    const parsed = Number.parseInt(value, 10);
    setDurationValue(Number.isNaN(parsed) ? 1 : Math.max(1, parsed));
  };

  const handleDurationUnitChange = (value: string) => {
    const unit = pickOption(DURATION_UNITS, value);
    if (unit !== undefined) setDurationUnit(unit);
  };

  const handleOperationChange = (value: string) => {
    const next = pickOption(BULK_OPERATIONS, value);
    if (next !== undefined) setOperation(next);
  };

  const isSubmitting = bulkAction.isPending;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <IconAction icon={ListChecks} label={t('audit.bulk.actions')} />
      </DialogTrigger>
      <DialogContent className="max-w-lg max-h-[80vh] overflow-hidden">
        <DialogHeader>
          <DialogTitle>{t('audit.bulk.title')}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 overflow-auto max-h-[60vh]">
          <Tabs value={operation} onValueChange={handleOperationChange}>
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="pardon">{t('audit.bulk.pardonAll')}</TabsTrigger>
              <TabsTrigger value="set-expiration">{t('audit.bulk.setExpiration')}</TabsTrigger>
            </TabsList>
          </Tabs>

          <div>
            <p id={typesId} className="mb-2 text-sm font-medium">{t('audit.bulk.punishmentTypes')}</p>
            {isError ? (
              <div className="flex items-center justify-between gap-2">
                <p className="text-sm text-destructive-text">{t('common.unknownError')}</p>
                <Button variant="outline" size="sm" onClick={() => { void typesQuery.refetch(); }}>
                  {t('common.retry')}
                </Button>
              </div>
            ) : !isComplete ? (
              <LoadingSpinner className="h-4 w-4 text-muted-foreground" />
            ) : activeTypes.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('audit.bulk.noActivePunishments')}</p>
            ) : (
              <div role="group" aria-labelledby={typesId} className="space-y-2 max-h-40 overflow-auto border rounded p-2">
                {activeTypes.map(({ ordinal, name, activeCount }) => (
                  <label key={ordinal} className="flex items-center gap-2 cursor-pointer hover:bg-muted/50 rounded p-1">
                    <Checkbox
                      checked={selectedTypes.has(ordinal)}
                      onCheckedChange={() => { toggleType(ordinal); }}
                    />
                    <span className="text-sm flex-1">{name}</span>
                    <span className="text-xs text-muted-foreground tabular-nums">{activeCount}</span>
                  </label>
                ))}
              </div>
            )}
          </div>

          <div>
            <Label htmlFor={reasonId} className="mb-2 block">{t('common.reason')}</Label>
            <Textarea
              id={reasonId}
              value={reason}
              onChange={e => setReason(e.target.value)}
              className="min-h-[60px]"
              maxLength={MAX_REASON_LENGTH}
            />
          </div>

          {operation === 'set-expiration' && (
            <div>
              <p className="mb-2 text-sm font-medium">{t('audit.bulk.newDuration')}</p>
              <div className="flex items-center gap-2">
                <label className="flex items-center gap-1 text-sm cursor-pointer">
                  <Checkbox
                    checked={isPermanent}
                    onCheckedChange={(checked) => setIsPermanent(checked === true)}
                  />
                  {t('audit.permanent')}
                </label>
              </div>
              {!isPermanent && (
                <div className="flex items-center gap-2 mt-2">
                  <Input
                    type="number"
                    min={1}
                    value={durationValue}
                    onChange={e => handleDurationValueChange(e.target.value)}
                    className="w-20"
                    aria-label={t('audit.bulk.newDuration')}
                  />
                  <Select value={durationUnit} onValueChange={handleDurationUnitChange}>
                    <SelectTrigger className="w-[120px]" aria-label={t('audit.bulk.newDuration')}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="minutes">{t('punishment.minutes')}</SelectItem>
                      <SelectItem value="hours">{t('punishment.hours')}</SelectItem>
                      <SelectItem value="days">{t('punishment.days')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              )}
            </div>
          )}

          {selectedTypes.size > 0 && (
            <div className="p-3 bg-muted/50 rounded-lg border text-sm">
              <Trans
                i18nKey="audit.bulk.preview"
                count={previewCount}
                components={{ bold: <span className="font-bold" /> }}
              />
            </div>
          )}

          <Button
            variant="destructive"
            className="w-full"
            onClick={() => { setConfirmOpen(true); }}
            disabled={isSubmitting || !isComplete || selectedTypes.size === 0 || !reason.trim()}
          >
            {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" />}
            {operation === 'pardon'
              ? t('audit.bulk.pardonCount', { count: previewCount })
              : t('audit.bulk.updateExpirationCount', { count: previewCount })}
          </Button>
        </div>
      </DialogContent>
      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('audit.bulk.confirmTitle')}</AlertDialogTitle>
            <AlertDialogDescription>
              {operation === 'pardon'
                ? t('audit.bulk.confirmPardon', { count: previewCount })
                : t('audit.bulk.confirmSetExpiration', { count: previewCount })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={performSubmit}
              className={buttonVariants({ variant: 'destructive' })}
            >
              {t('common.confirm')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Dialog>
  );
}
