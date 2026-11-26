import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight, MessageCircle } from 'lucide-react';
import { BrandLogo } from '@/components/brand/BrandLogo';
import { HOME_ROW_ACTION_CLASS, HOME_ROW_CHEVRON_CLASS, HOME_ROW_CLASS, HomeRowBody } from '@/components/home/HomeRow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { usePublicFont, useStyleVariables } from '@/hooks/use-public-theme';
import { publicBackdropStyle, publicThemeVariables, type ColorScheme, type PublicTheme } from '@/lib/public-theme';
import { cn } from '@/lib/utils';

const PREVIEW_SCHEMES: readonly ColorScheme[] = ['light', 'dark'];

function PreviewPane({ theme, scheme }: { theme: PublicTheme; scheme: ColorScheme }) {
  const { t } = useTranslation();
  const [element, setElement] = useState<HTMLDivElement | null>(null);
  const variables = useMemo(() => publicThemeVariables(theme, scheme), [theme, scheme]);
  useStyleVariables(element, variables);

  return (
    <div
      ref={setElement}
      inert
      className={cn(scheme, 'space-y-4 overflow-hidden rounded-lg border bg-background p-6 font-sans text-foreground')}
      style={publicBackdropStyle(theme)}
    >
      <div className="mx-auto h-14 w-14">
        <BrandLogo icon="homepage" wordmarkClassName="flex h-full w-full items-center justify-center text-xl" imageClassName="h-full w-full object-contain" />
      </div>
      <Input type="search" placeholder={t('home.searchPlaceholder')} aria-label={t('home.searchPlaceholder')} />
      <div className={HOME_ROW_CLASS}>
        <div className={HOME_ROW_ACTION_CLASS}>
          <HomeRowBody
            icon={MessageCircle}
            title={t('home.defaults.contactTitle')}
            description={t('home.defaults.contactDesc')}
            accent={theme.accentColor !== ''}
            trailing={<ChevronRight className={HOME_ROW_CHEVRON_CLASS} aria-hidden="true" />}
          />
        </div>
      </div>
      <Button type="button" className="w-full">{t('submitTicket.submitTicket')}</Button>
    </div>
  );
}

export function PublicThemePreview({ theme }: { theme: PublicTheme }) {
  usePublicFont(theme.font);
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {PREVIEW_SCHEMES.map((scheme) => <PreviewPane key={scheme} theme={theme} scheme={scheme} />)}
    </div>
  );
}
