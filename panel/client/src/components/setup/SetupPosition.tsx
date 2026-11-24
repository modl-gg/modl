import { useTranslation } from 'react-i18next';
import { SETUP_CODE_POSITION, SETUP_CODE_SCREEN, SETUP_SCREEN_COUNT, stepPosition, type SetupScreen } from '@/lib/setup-steps';

export function SetupPosition({ screen }: { screen: SetupScreen }) {
  const { t } = useTranslation();
  const position = screen === SETUP_CODE_SCREEN ? SETUP_CODE_POSITION : stepPosition(screen);

  return <p className="mb-2 text-sm font-medium text-muted-foreground md:hidden">{t('pages.setup.stepper.position', { current: position, total: SETUP_SCREEN_COUNT })}</p>;
}
