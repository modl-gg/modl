import '@fontsource/audiowide/latin-400.css';
import { useTranslation } from 'react-i18next';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { cn } from '@/lib/utils';

const WORDMARK = 'modl';

export type BrandIconKind = 'panel' | 'homepage';

interface BrandLogoProps {
  icon: BrandIconKind;
  wordmarkClassName: string;
  imageClassName: string;
  decorative?: boolean;
}

export function useBrandIconUrl(icon: BrandIconKind): string {
  const { data: publicSettings } = usePublicSettings();
  return (icon === 'panel' ? publicSettings?.panelIconUrl : publicSettings?.homepageIconUrl) ?? '';
}

export function ModlWordmark({ className }: { className: string }) {
  return <span className={cn('font-brand leading-none text-primary select-none', className)}>{WORDMARK}</span>;
}

export function BrandLogo({ icon, wordmarkClassName, imageClassName, decorative = false }: BrandLogoProps) {
  const { t } = useTranslation();
  const { data: publicSettings } = usePublicSettings();
  const iconUrl = useBrandIconUrl(icon);

  if (iconUrl === '') return <ModlWordmark className={wordmarkClassName} />;
  const alt = decorative ? '' : publicSettings?.serverDisplayName || t('home.serverLogo');
  return <img src={iconUrl} alt={alt} className={imageClassName} />;
}

export function SignInBrand() {
  return (
    <div className="mb-8 flex justify-center">
      <BrandLogo icon="panel" wordmarkClassName="text-5xl" imageClassName="h-16 w-auto max-w-full object-contain" />
    </div>
  );
}
