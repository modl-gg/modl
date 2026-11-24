import type { SetupEnvironment } from '@/hooks/data/setup';

export interface SetupStepProps {
  environment: SetupEnvironment;
  done: boolean;
}
