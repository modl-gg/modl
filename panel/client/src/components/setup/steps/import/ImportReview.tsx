import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import {
  useHostedImportPreview,
  useHostedImportStaff,
  useStartImport,
  type ImportPreviewView,
  type ImportState,
  type RetentionChoiceId,
  type RetentionOption,
} from '@/hooks/data/hosted-import';
import { apiErrorCode } from '@/lib/http-error';
import { formatFileSize } from '@/utils/file-utils';
import { ImportIssues, ImportTotals } from './ImportIssues';
import { errorKey } from './import-labels';
import { useImportDiscard } from './use-import-discard';

const BLOCKED_KEYS: Readonly<Record<string, string>> = {
  invalidEmail: 'pages.setup.import.staff.blockedInvalidEmail',
  duplicateEmail: 'pages.setup.import.staff.blockedDuplicateEmail',
  owner: 'pages.setup.import.staff.blockedOwner',
};

function isRetentionChoice(value: string): value is RetentionChoiceId {
  return value === 'keepHistory' || value === 'standard';
}

function RetentionChoice({ options, value, onChange }: {
  options: readonly RetentionOption[];
  value: RetentionChoiceId | null;
  onChange: (choice: RetentionChoiceId) => void;
}) {
  const { t } = useTranslation();
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">{t('pages.setup.import.retention.title')}</legend>
      <RadioGroup value={value ?? ''} onValueChange={(next) => { if (isRetentionChoice(next)) onChange(next); }}>
        {options.map((option) => (
          <div key={option.choice} className="flex items-start gap-2 rounded-md border p-3">
            <RadioGroupItem id={`retention-${option.choice}`} value={option.choice} />
            <Label htmlFor={`retention-${option.choice}`} className="space-y-1">
              <span className="block font-medium">
                {t(option.choice === 'keepHistory' ? 'pages.setup.import.retention.keep' : 'pages.setup.import.retention.standard')}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t('pages.setup.import.retention.days', { chat: option.chatLogDays, audit: option.auditLogDays })}
              </span>
              <span className="block text-xs text-muted-foreground">
                {option.replayRetentionEnabled
                  ? t('pages.setup.import.retention.replayDays', { days: option.replayDays })
                  : t('pages.setup.import.retention.untilDeleted')}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t('pages.setup.import.retention.space', { size: formatFileSize(option.requiredBytes) })}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t(option.keepsIpHistory ? 'pages.setup.import.retention.ipKept' : 'pages.setup.import.retention.ipStandard')}
              </span>
              {option.rowsNotKept + option.replaysNotKept > 0 && (
                <span className="block text-xs text-muted-foreground">
                  {t('pages.setup.import.retention.notKept', { count: option.rowsNotKept + option.replaysNotKept })}
                </span>
              )}
            </Label>
          </div>
        ))}
      </RadioGroup>
    </fieldset>
  );
}

function StaffSelection({ preview, selected, onChange }: {
  preview: ImportPreviewView;
  selected: ReadonlySet<string>;
  onChange: (next: Set<string>) => void;
}) {
  const { t } = useTranslation();
  const [cursor, setCursor] = useState('');
  const page = useHostedImportStaff(preview.staffTotal > 0, cursor);
  const candidates = page.data?.staff ?? [];
  const nextCursor = page.data?.nextCursor ?? '';
  const selectable = candidates.filter((candidate) => candidate.selectable);

  const toggle = (sourceId: string, checked: boolean) => {
    const next = new Set(selected);
    if (checked) next.add(sourceId);
    else next.delete(sourceId);
    onChange(next);
  };

  if (preview.staffTotal === 0) return null;
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">{t('pages.setup.import.staff.title')}</legend>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" size="sm"
          onClick={() => onChange(new Set([...selected, ...selectable.map((candidate) => candidate.sourceId)]))}>
          {t('pages.setup.import.staff.selectAll')}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={() => onChange(new Set())}>
          {t('pages.setup.import.staff.selectNone')}
        </Button>
        <span className="self-center text-xs text-muted-foreground">{t('pages.setup.import.staff.selected', { count: selected.size })}</span>
      </div>
      {page.isLoading && <LoadingSpinner className="h-4 w-4" />}
      <ul className="space-y-1">
        {candidates.map((candidate) => (
          <li key={candidate.sourceId} className="flex items-start gap-2 rounded-md border p-2 text-sm">
            <Checkbox id={`staff-${candidate.sourceId}`} disabled={!candidate.selectable} checked={selected.has(candidate.sourceId)}
              onCheckedChange={(checked) => toggle(candidate.sourceId, checked === true)} />
            <Label htmlFor={`staff-${candidate.sourceId}`} className="min-w-0 space-y-0.5">
              <span className="block font-medium">{candidate.username === '' ? candidate.email : `${candidate.username} (${candidate.email})`}</span>
              <span className="block text-xs text-muted-foreground">
                {t('pages.setup.import.staff.roleLine', {
                  role: candidate.roleName === '' ? t('pages.setup.import.staff.noRole') : candidate.roleName,
                  count: candidate.permissions.length,
                })}
              </span>
              {candidate.minecraftUsername !== '' && (
                <span className="block text-xs text-muted-foreground">{t('pages.setup.import.staff.minecraft', { name: candidate.minecraftUsername })}</span>
              )}
              {!candidate.selectable && (
                <span className="block text-xs text-warning">{t(BLOCKED_KEYS[candidate.blockedCode] ?? 'pages.setup.import.staff.blocked')}</span>
              )}
            </Label>
          </li>
        ))}
      </ul>
      {nextCursor !== '' && (
        <Button type="button" variant="ghost" size="sm" onClick={() => setCursor(nextCursor)}>
          {t('pages.setup.import.staff.loadMore')}
        </Button>
      )}
    </fieldset>
  );
}

export function ImportReview({ state }: { state: ImportState }) {
  const { t, i18n } = useTranslation();
  const preview = useHostedImportPreview(true);
  const start = useStartImport();
  const discard = useImportDiscard(state.importId);
  const [retention, setRetention] = useState<RetentionChoiceId | null>(null);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [mergeOwner, setMergeOwner] = useState(false);
  const [confirmed, setConfirmed] = useState(false);

  if (preview.isError) return <Notice variant="error" announce>{t('pages.setup.import.errors.wrongState')}</Notice>;
  if (!preview.data) return <LoadingSpinner className="h-5 w-5" />;
  const view = preview.data;
  const diskShort = view.availableBytes < (view.retention.find((option) => option.choice === retention)?.requiredBytes ?? 0);
  const canStart = retention !== null && confirmed && !diskShort && !start.isPending;

  const begin = () => {
    if (retention === null) return;
    start.mutate({ importId: state.importId, staffIds: [...selected], mergeArchivedOwner: mergeOwner, retention });
  };

  return (
    <div className="space-y-5">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">{t('pages.setup.import.review.title')}</h3>
        <p className="text-sm text-muted-foreground">
          {t('pages.setup.import.review.source', { name: view.serverName === '' ? view.subdomain : view.serverName })}
        </p>
        {view.exportedAt !== null && (
          <p className="text-xs text-muted-foreground">{t('pages.setup.import.review.exportedAt', { date: new Date(view.exportedAt).toLocaleString(i18n.language) })}</p>
        )}
      </div>
      <ImportTotals counts={view.counts} />
      <ImportIssues issues={view.issues} />
      <Notice variant="warning">{t('pages.setup.import.review.replaces')}</Notice>
      {!view.ownerEmailsMatch && (
        <div className="space-y-2">
          <p className="text-sm">{t('pages.setup.import.owner.different', { archived: view.archivedOwnerEmail, local: view.localOwnerEmail })}</p>
          {view.archivedOwnerHasStaffRecord && (
            <div className="flex items-start gap-2">
              <Checkbox id="import-merge-owner" checked={mergeOwner} onCheckedChange={(checked) => setMergeOwner(checked === true)} />
              <Label htmlFor="import-merge-owner" className="space-y-0.5">
                <span className="block">{t('pages.setup.import.owner.merge', { email: view.archivedOwnerEmail })}</span>
                <span className="block text-xs text-muted-foreground">{t('pages.setup.import.owner.mergeDesc')}</span>
              </Label>
            </div>
          )}
        </div>
      )}
      {view.archivedOwnerMinecraftUsername !== '' && (
        <p className="text-xs text-muted-foreground">{t('pages.setup.import.owner.minecraftHint', { name: view.archivedOwnerMinecraftUsername })}</p>
      )}
      <StaffSelection preview={view} selected={selected} onChange={setSelected} />
      <RetentionChoice options={view.retention} value={retention} onChange={setRetention} />
      <p className="text-xs text-muted-foreground">{t('pages.setup.import.review.disk', { size: formatFileSize(view.availableBytes) })}</p>
      {diskShort && <Notice variant="error">{t('pages.setup.import.review.diskShort')}</Notice>}
      <div className="flex items-start gap-2">
        <Checkbox id="import-confirm" checked={confirmed} onCheckedChange={(checked) => setConfirmed(checked === true)} />
        <Label htmlFor="import-confirm">{t('pages.setup.import.review.confirm')}</Label>
      </div>
      <p className="text-xs text-muted-foreground">{t('pages.setup.import.trustWarning')}</p>
      {start.error !== null && <Notice variant="error" announce>{t(errorKey(apiErrorCode(start.error)))}</Notice>}
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={!canStart} onClick={begin}>{t('pages.setup.import.review.start')}</Button>
        <Button type="button" variant="outline" disabled={discard.pending} onClick={() => { void discard.confirmDiscard(); }}>
          {t('pages.setup.import.review.discard')}
        </Button>
      </div>
      {discard.dialog}
    </div>
  );
}
