import { LookupChoice } from '@/components/settings/LookupChoice';
import { usePlayerAddressSettings } from '@/hooks/data/player-addresses';
import { StepLoadState } from './StepLoadState';

export function PlayerAddressesStep() {
  const settings = usePlayerAddressSettings(true);

  if (settings.data === undefined) {
    return <StepLoadState isError={settings.isError} onRetry={() => { void settings.refetch(); }} />;
  }
  return <LookupChoice settings={settings.data} />;
}
