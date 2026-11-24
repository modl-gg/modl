import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { Loader2 } from 'lucide-react';
import { CheckStatus, type CheckState } from '@/components/ui/check-status';
import { PanelUrlField } from '@/components/settings/PanelUrlField';
import { usePublicUrlCheck, type PublicUrlReachability, type SetupHostId } from '@/hooks/data/setup';
import { useGeneralSettings, type GeneralSettingsSnapshot, type PanelUrlSaveOutcome } from '@/hooks/data/settings';
import { panelUrlWarning, type PanelUrlWarning } from '@/lib/http-origin';
import { usePanelUrlSave } from './use-panel-url-save';
import { StepLoadState } from './StepLoadState';
import type { SetupStepProps } from './step-props';

interface CheckProblem {
  variant: 'warning' | 'error';
  messageKey: string;
}

const REACHABILITY_PROBLEMS: Record<PublicUrlReachability, CheckProblem | null> = {
  reachable: null,
  unreachable: { variant: 'error', messageKey: 'pages.setup.address.unreachable' },
  'other-service': { variant: 'warning', messageKey: 'pages.setup.address.otherService' },
  invalid: { variant: 'error', messageKey: 'pages.setup.address.invalid' },
};

const CHECK_FAILED: CheckProblem = { variant: 'error', messageKey: 'pages.setup.address.checkFailed' };

const WARNING_KEYS: Record<PanelUrlWarning, string> = {
  'local-only': 'pages.setup.address.localOnly',
  insecure: 'pages.setup.address.insecure',
};

const PROXY_HINT_KEYS: Record<SetupHostId, string> = {
  plugin: 'pages.setup.address.reverseProxyPlugin',
  standalone: 'pages.setup.address.reverseProxyStandalone',
};

interface Reachability {
  checking: boolean;
  problem: CheckProblem | null;
  checkAgain: () => void;
}

function useReachability(url: string): Reachability {
  const check = usePublicUrlCheck(url);
  const problemOf = (): CheckProblem | null => {
    if (check.isFetching) return null;
    if (check.isError || !check.data) return CHECK_FAILED;
    return REACHABILITY_PROBLEMS[check.data.reachability];
  };
  return {
    checking: check.isFetching,
    problem: problemOf(),
    checkAgain: () => { void check.refetch(); },
  };
}

function checkStateOf(problem: CheckProblem | null): CheckState {
  if (problem === null) return 'passed';
  return problem.variant === 'warning' ? 'warning' : 'failed';
}

function ReachabilityIndicator({ reachability }: { reachability: Reachability }) {
  const { t } = useTranslation();

  return (
    <span role="status" className="flex items-center">
      {reachability.checking
        ? <Loader2 role="img" className="h-4 w-4 animate-spin text-muted-foreground" aria-label={t('pages.setup.address.checking')} />
        : <CheckStatus state={checkStateOf(reachability.problem)} />}
    </span>
  );
}

function ReachabilityProblem({ reachability }: { reachability: Reachability }) {
  const { t } = useTranslation();
  const { problem } = reachability;

  if (problem === null) return null;
  return (
    <Notice variant={problem.variant} announce>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span>{t(problem.messageKey)}</span>
        <Button type="button" variant="outline" size="sm" onClick={reachability.checkAgain}>
          {t('pages.setup.address.checkAgain')}
        </Button>
      </div>
    </Notice>
  );
}

function AddressForm({ saved, host, reverseProxySuspected }: { saved: GeneralSettingsSnapshot; host: SetupHostId; reverseProxySuspected: boolean }) {
  const { t } = useTranslation();
  const savePanelUrl = usePanelUrlSave();
  const warning = panelUrlWarning(saved.publicUrl);
  const reachability = useReachability(saved.publicUrl);

  const savePublicUrl = async (origin: string): Promise<PanelUrlSaveOutcome> => savePanelUrl(origin, saved.version);

  return (
    <div className="space-y-4">
      <PanelUrlField
        publicUrl={saved.publicUrl}
        managedByConfig={saved.publicUrlManagedByConfig}
        onSave={savePublicUrl}
        status={saved.publicUrl ? <ReachabilityIndicator reachability={reachability} /> : null}
      />
      {saved.publicUrl && <ReachabilityProblem reachability={reachability} />}
      {warning && <Notice variant="warning">{t(WARNING_KEYS[warning])}</Notice>}
      {reverseProxySuspected && (
        <Notice variant="info">{t(PROXY_HINT_KEYS[host])}</Notice>
      )}
    </div>
  );
}

export function AddressStep({ environment }: SetupStepProps) {
  const { data: saved, isError, refetch } = useGeneralSettings();

  if (!saved) {
    return <StepLoadState isError={isError} onRetry={() => { void refetch(); }} />;
  }
  return <AddressForm saved={saved} host={environment.host} reverseProxySuspected={environment.reverseProxySuspected} />;
}
