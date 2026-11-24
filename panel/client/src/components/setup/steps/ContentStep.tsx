import { useTranslation } from 'react-i18next';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { usePunishmentTypes, useTicketFormSettings } from '@/hooks/use-data';
import { useKnowledgebaseCategories } from '@/hooks/data/knowledgebase';
import { useHomepageCards } from '@/hooks/data/homepage';
import { TICKET_FORM_TYPES } from '@/types/forms';
import { SetupNextSteps } from '../SetupNextSteps';

interface ContentSummary {
  labelKey: string;
  count: number | null | undefined;
}

interface CountableQuery<T> {
  data: T | undefined;
  isError: boolean;
}

function countOf<T>(query: CountableQuery<T>, count: (data: T) => number): number | null | undefined {
  if (query.isError) return null;
  return query.data === undefined ? undefined : count(query.data);
}

type TicketFormsEnvelope = NonNullable<ReturnType<typeof useTicketFormSettings>['data']>;

function ticketFormCount(envelope: TicketFormsEnvelope | null): number {
  const forms = envelope?.data;
  if (!forms) return 0;
  return TICKET_FORM_TYPES.filter((type) => forms[type] !== undefined).length;
}

function SummaryCount({ count }: { count: number | null | undefined }) {
  const { t } = useTranslation();

  if (count === undefined) return <LoadingSpinner className="h-4 w-4" />;
  return <>{count ?? t('common.notAvailable')}</>;
}

function SummaryRow({ labelKey, count }: ContentSummary) {
  const { t } = useTranslation();

  return (
    <li className="flex items-center justify-between gap-3 px-3 py-2.5">
      <span className="text-sm">{t(labelKey)}</span>
      <span className="text-sm tabular-nums text-muted-foreground">
        <SummaryCount count={count} />
      </span>
    </li>
  );
}

export function ContentStep() {
  const punishmentTypes = usePunishmentTypes();
  const ticketForms = useTicketFormSettings();
  const categories = useKnowledgebaseCategories();
  const homepageCards = useHomepageCards();

  const summaries: ContentSummary[] = [
    {
      labelKey: 'pages.setup.content.punishmentTypes',
      count: countOf(punishmentTypes, (types) => types.length),
    },
    {
      labelKey: 'pages.setup.content.ticketForms',
      count: countOf(ticketForms, ticketFormCount),
    },
    {
      labelKey: 'pages.setup.content.knowledgebaseCategories',
      count: countOf(categories, (list) => list.length),
    },
    {
      labelKey: 'pages.setup.content.homepageCards',
      count: countOf(homepageCards, (cards) => cards.length),
    },
  ];

  return (
    <div className="space-y-8">
      <ul className="max-w-md divide-y rounded-md border">
        {summaries.map((summary) => <SummaryRow key={summary.labelKey} {...summary} />)}
      </ul>
      <SetupNextSteps />
    </div>
  );
}
