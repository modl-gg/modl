import { useTranslation } from 'react-i18next';
import { Link } from 'wouter';
import { cn } from '@/lib/utils';
import {
  SETUP_CODE_SCREEN,
  isStepReachable,
  setupPath,
  type SetupScreen,
  type SetupStepProgress,
  type SetupStepStatusId,
} from '@/lib/setup-steps';
import { SETUP_STEP_REGISTRY } from './steps/registry';

const STATUS_LABEL_KEYS: Record<SetupStepStatusId, string> = {
  done: 'pages.setup.stepper.done',
  pending: 'pages.setup.stepper.pending',
};

interface SetupStepperProps {
  progress: readonly SetupStepProgress[];
  current: SetupScreen;
}

interface StepperRowProps {
  title: string;
  status: SetupStepStatusId;
  isCurrent: boolean;
  href: string | null;
}

function StepperRow({ title, status, isCurrent, href }: StepperRowProps) {
  const { t } = useTranslation();
  const className = cn(
    'flex items-center gap-3 rounded-md px-3 py-2 text-sm',
    isCurrent ? 'bg-primary/10 font-semibold text-foreground' : 'text-muted-foreground',
    href !== null && !isCurrent && 'transition-colors hover:bg-muted hover:text-foreground',
  );
  const content = (
    <>
      <span
        aria-hidden="true"
        className={cn(
          'h-3 w-3 shrink-0 rounded-full border-2',
          status === 'done' && 'border-primary bg-primary',
          status !== 'done' && !isCurrent && 'border-muted-foreground/30',
          status !== 'done' && isCurrent && 'border-primary bg-background ring-4 ring-primary/25',
        )}
      />
      <span className="flex-1">{title}</span>
      <span className="sr-only">{t(STATUS_LABEL_KEYS[status])}</span>
    </>
  );

  return (
    <li aria-current={isCurrent ? 'step' : undefined}>
      {href === null ? <div className={className}>{content}</div> : <Link href={href} className={className}>{content}</Link>}
    </li>
  );
}

export function SetupStepper({ progress, current }: SetupStepperProps) {
  const { t } = useTranslation();
  const statusByStep = new Map(progress.map((entry) => [entry.step, entry.status]));
  const atCode = current === SETUP_CODE_SCREEN;

  return (
    <nav aria-label={t('pages.setup.stepper.label')}>
      <ol className="flex flex-col gap-1">
        <StepperRow title={t('pages.setup.code.title')} status={atCode ? 'pending' : 'done'} isCurrent={atCode} href={null} />
        {SETUP_STEP_REGISTRY.map(({ step, titleKey }) => (
          <StepperRow
            key={step}
            title={t(titleKey)}
            status={statusByStep.get(step) ?? 'pending'}
            isCurrent={step === current}
            href={!atCode && isStepReachable(step, progress) ? setupPath(step) : null}
          />
        ))}
      </ol>
    </nav>
  );
}
