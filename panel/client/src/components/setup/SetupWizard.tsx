import { useCallback, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Redirect, useLocation } from 'wouter';
import { PageSubmitContext } from '@/components/ui/action-form';
import { Button } from '@/components/ui/button';
import { LoadingSpinner } from '@/components/ui/loading-spinner';
import { Notice } from '@/components/ui/notice';
import { useRecordSetupStep, useSetupWizard, type SetupWizard as SetupWizardState } from '@/hooks/data/setup';
import { isImportInProgress, useHostedImport } from '@/hooks/data/hosted-import';
import { PANEL_HOME_PATH } from '@/lib/return-path';
import { adjacentStep, landingStep, parseSetupStep, reachableStep, setupPath, type SetupStepId } from '@/lib/setup-steps';
import { ContinueGateContext, allowContinue, type BeforeContinue, type ContinueGate } from '@/hooks/use-before-continue';
import { useAuth } from '@/hooks/use-auth';
import { setupStepDefinition } from './steps/registry';
import { SetupChoicesProvider } from './setup-choices';
import { SetupShell } from './SetupShell';
import { SetupActionButton, SetupStepFooter } from './SetupStepFooter';
import { SetupStepper } from './SetupStepper';

function useSetupActions() {
  const [, navigate] = useLocation();
  const recordStep = useRecordSetupStep();
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);

  const goTo = (step: SetupStepId) => navigate(setupPath(step));

  const continueFrom = async (step: SetupStepId, allowed: () => Promise<boolean>) => {
    setPending(true);
    setFailed(false);
    try {
      if (!(await allowed())) return;
      await recordStep.mutateAsync(step);
      const next = adjacentStep(step, 1);
      navigate(next === null ? PANEL_HOME_PATH : setupPath(next));
    } catch {
      setFailed(true);
    } finally {
      setPending(false);
    }
  };

  return { pending, failed, goTo, continueFrom };
}

function useContinueGate() {
  const handlersRef = useRef<Set<BeforeContinue>>(new Set());
  const register = useCallback((handler: BeforeContinue) => {
    handlersRef.current.add(handler);
    return () => {
      handlersRef.current.delete(handler);
    };
  }, []);
  const [unreadyReporters, setUnreadyReporters] = useState<ReadonlySet<string>>(() => new Set());
  const reportReady = useCallback((reporter: string, ready: boolean) => {
    setUnreadyReporters((current) => {
      if (current.has(reporter) !== ready) return current;
      const next = new Set(current);
      if (ready) next.delete(reporter);
      else next.add(reporter);
      return next;
    });
  }, []);
  const gate = useMemo<ContinueGate>(() => ({ register, reportReady }), [register, reportReady]);
  const allowed = async () => allowContinue(handlersRef.current);

  return { gate, ready: unreadyReporters.size === 0, allowed };
}

function SetupWizardScreen({ wizard, step }: { wizard: SetupWizardState; step: SetupStepId }) {
  const { t } = useTranslation();
  const actions = useSetupActions();
  const continueGate = useContinueGate();
  const hostedImport = useHostedImport();
  const importLocked = isImportInProgress(hostedImport.data);
  const definition = setupStepDefinition(step);
  const StepComponent = definition.component;
  const done = wizard.steps.find((entry) => entry.step === step)?.status === 'done';
  const previous = adjacentStep(step, -1);
  const next = adjacentStep(step, 1);
  const continueBlocked = actions.pending || importLocked || !continueGate.ready;
  const continueStep = () => {
    if (!continueBlocked) void actions.continueFrom(step, continueGate.allowed);
  };
  const pageSubmit = { submit: continueStep, canSubmit: !continueBlocked };

  return (
    <SetupShell
      screen={step}
      title={t(definition.titleKey)}
      intro={[t(definition.introKey)]}
      stepper={<SetupStepper progress={wizard.steps} current={step} />}
      footer={(
        <SetupStepFooter busy={actions.pending} failed={actions.failed} onBack={previous === null ? null : () => { actions.goTo(previous); }}>
          <SetupActionButton
            pending={actions.pending}
            busy={continueBlocked}
            onClick={continueStep}
          >
            {t(next === null ? 'pages.setup.nav.finish' : 'pages.setup.nav.continue')}
          </SetupActionButton>
        </SetupStepFooter>
      )}
    >
      <ContinueGateContext value={continueGate.gate}>
        <PageSubmitContext value={pageSubmit}>
          <StepComponent environment={wizard.environment} done={done} />
        </PageSubmitContext>
      </ContinueGateContext>
    </SetupShell>
  );
}

function SetupWizardRoute({ requested }: { requested: string | undefined }) {
  const { t } = useTranslation();
  const { data: wizard, isError, refetch } = useSetupWizard();

  if (!wizard) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background p-4">
        {isError ? (
          <div className="w-full max-w-md space-y-3">
            <Notice variant="error" announce>{t('pages.setup.wizardLoadFailed')}</Notice>
            <Button type="button" variant="outline" className="w-full" onClick={() => { void refetch(); }}>
              {t('common.retry')}
            </Button>
          </div>
        ) : (
          <LoadingSpinner className="h-6 w-6 text-primary" />
        )}
      </div>
    );
  }

  if (wizard.completed) {
    return <Redirect to={PANEL_HOME_PATH} replace />;
  }
  const step = parseSetupStep(requested);
  if (step === null) {
    return <Redirect to={setupPath(landingStep(wizard.steps))} replace />;
  }
  const reachable = reachableStep(step, wizard.steps);
  if (reachable !== step) {
    return <Redirect to={setupPath(reachable)} replace />;
  }
  return <SetupWizardScreen key={step} wizard={wizard} step={step} />;
}

export function SetupWizard({ requested }: { requested: string | undefined }) {
  const { user } = useAuth();
  return (
    <SetupChoicesProvider key={user?.id ?? ''}>
      <SetupWizardRoute requested={requested} />
    </SetupChoicesProvider>
  );
}
