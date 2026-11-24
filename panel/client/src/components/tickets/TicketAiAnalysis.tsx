import { useTranslation } from 'react-i18next';
import type { PunishmentType } from '@proto/modl/v1/settings_pb.ts';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useToast } from '@/hooks/use-toast';
import { useApplyTicketAiSuggestion, useDismissTicketAiSuggestion, type PanelTicketAiAnalysis } from '@/hooks/data/tickets';
import { describeError } from '@/lib/http-error';

interface TicketAiAnalysisProps {
  ticketId: string;
  reportedPlayerUuid: string;
  analysis: PanelTicketAiAnalysis;
  isClosed: boolean;
  staffName: string | undefined;
  punishmentTypes: readonly PunishmentType[];
}

function aiStatusKey(analysis: PanelTicketAiAnalysis): string {
  if (analysis.wasAppliedAutomatically) return 'ticket.ai.appliedAutomatically';
  return analysis.dismissed ? 'ticket.ai.dismissedStatus' : 'ticket.ai.appliedByStaff';
}

function AiStatusNotice({ analysis }: { analysis: PanelTicketAiAnalysis }) {
  const { t } = useTranslation();
  return (
    <p className="text-sm text-muted-foreground">{t(aiStatusKey(analysis))}</p>
  );
}

function AiSuggestionCard({ ticketId, reportedPlayerUuid, analysis, staffName, punishmentTypes }: Omit<TicketAiAnalysisProps, 'isClosed'>) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const applySuggestion = useApplyTicketAiSuggestion();
  const dismissSuggestion = useDismissTicketAiSuggestion();
  const analyzedOn = t('ticket.ai.analyzedOn', { date: new Date(analysis.createdAt).toLocaleString() });
  const suggestedAction = analysis.suggestedAction;
  const suggestedTypeName = suggestedAction
    ? punishmentTypes.find((type) => type.ordinal === suggestedAction.punishmentTypeId)?.name ?? t('ticket.ai.unknownPunishment')
    : '';
  const busy = applySuggestion.isPending || dismissSuggestion.isPending;

  const apply = () => {
    applySuggestion.mutate({ ticketId, staffName, reportedPlayerUuid }, {
      onSuccess: () => {
        toast({ title: t('toast.success'), description: t('ticket.aiApplied') });
      },
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('ticket.aiApplyFailed')), variant: 'destructive' });
      },
    });
  };

  const dismiss = () => {
    dismissSuggestion.mutate(ticketId, {
      onSuccess: () => {
        toast({ title: t('toast.success'), description: t('ticket.aiDismissed') });
      },
      onError: (error) => {
        toast({ title: t('toast.error'), description: describeError(error, t('ticket.aiDismissFailed')), variant: 'destructive' });
      },
    });
  };

  return (
    <Card className="shadow-card" data-testid="ai-analysis">
      <CardHeader>
        <CardTitle>{t('ticket.ai.suggestionTitle')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm">{analysis.analysis}</p>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t pt-3">
          <div>
            <p className="text-sm font-medium">
              {suggestedAction
                ? t('ticket.ai.suggested', { name: suggestedTypeName, severity: suggestedAction.severity })
                : t('ticket.ai.noActionRequired')}
            </p>
            <p className="text-xs text-muted-foreground">{analyzedOn}</p>
          </div>
          {suggestedAction && (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={busy} onClick={apply}>
                {t('ticket.ai.apply')}
              </Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={dismiss}>
                {t('ticket.ai.dismiss')}
              </Button>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

export function TicketAiAnalysis({ ticketId, reportedPlayerUuid, analysis, isClosed, staffName, punishmentTypes }: TicketAiAnalysisProps) {
  const settled = analysis.wasAppliedAutomatically || analysis.dismissed || Boolean(analysis.appliedBy);
  const showSuggestion = !settled && !isClosed;

  return (
    <>
      {showSuggestion && (
        <AiSuggestionCard ticketId={ticketId} reportedPlayerUuid={reportedPlayerUuid} analysis={analysis} staffName={staffName} punishmentTypes={punishmentTypes} />
      )}
      {settled && <AiStatusNotice analysis={analysis} />}
    </>
  );
}
