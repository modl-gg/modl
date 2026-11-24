import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'wouter';
import { Notice } from '@/components/ui/notice';
import { useRecordSetupStep } from '@/hooks/data/setup';
import { UPSTREAM_SOURCE_URL, WIKI_URL } from '@/lib/project-links';
import { settingsPagePath, type SettingsPageId } from '@/lib/settings-access';
import { LAST_SETUP_STEP } from '@/lib/setup-steps';

interface NextStep {
  titleKey: string;
  descriptionKey: string;
  page: SettingsPageId;
}

const NEXT_STEPS: readonly NextStep[] = [
  { titleKey: 'settings.pages.minecraftServers', descriptionKey: 'pages.setup.finish.servers', page: 'minecraft-servers' },
  { titleKey: 'settings.pages.roles', descriptionKey: 'pages.setup.finish.roles', page: 'roles' },
  { titleKey: 'settings.pages.punishmentTypes', descriptionKey: 'pages.setup.finish.punishmentTypes', page: 'punishment-types' },
  { titleKey: 'settings.pages.forms', descriptionKey: 'pages.setup.finish.forms', page: 'forms' },
  { titleKey: 'settings.pages.security', descriptionKey: 'pages.setup.finish.security', page: 'security' },
];

const CARD_CLASS = 'block rounded-md border p-4 text-left transition-colors hover:bg-muted/50 disabled:pointer-events-none disabled:opacity-50';

function CardBody({ title, description }: { title: string; description: string }) {
  return (
    <>
      <span className="block text-sm font-medium">{title}</span>
      <span className="mt-1 block text-sm text-muted-foreground">{description}</span>
    </>
  );
}

function useFinishAndOpen() {
  const [, navigate] = useLocation();
  const recordStep = useRecordSetupStep();
  const [failed, setFailed] = useState(false);

  const finishAndOpen = async (path: string) => {
    setFailed(false);
    try {
      await recordStep.mutateAsync(LAST_SETUP_STEP);
      navigate(path);
    } catch {
      setFailed(true);
    }
  };

  return { pending: recordStep.isPending, failed, finishAndOpen };
}

export function SetupNextSteps() {
  const { t } = useTranslation();
  const finish = useFinishAndOpen();

  return (
    <div className="space-y-6">
      {finish.failed && <Notice variant="error" announce>{t('pages.setup.nav.saveFailed')}</Notice>}
      <div className="grid gap-3 sm:grid-cols-2">
        {NEXT_STEPS.map(({ titleKey, descriptionKey, page }) => (
          <button
            key={page}
            type="button"
            className={CARD_CLASS}
            disabled={finish.pending}
            onClick={() => { void finish.finishAndOpen(settingsPagePath(page)); }}
          >
            <CardBody title={t(titleKey)} description={t(descriptionKey)} />
          </button>
        ))}
        <a href={WIKI_URL} target="_blank" rel="noopener noreferrer" className={CARD_CLASS}>
          <CardBody title={t('pages.setup.finish.docsLink')} description={t('pages.setup.finish.docs')} />
        </a>
      </div>
      <a href={`${UPSTREAM_SOURCE_URL}/issues`} target="_blank" rel="noopener noreferrer" className="text-xs text-muted-foreground hover:underline">
        {t('pages.setup.finish.issuesLink')}
      </a>
    </div>
  );
}
