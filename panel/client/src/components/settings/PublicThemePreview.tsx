import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { MessageCircle } from 'lucide-react';
import { BrandLogo } from '@/components/brand/BrandLogo';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
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
      <Card className="shadow-card">
        <CardContent className="space-y-4 p-6 text-center">
          <MessageCircle className={cn('mx-auto h-6 w-6', theme.accentColor === '' ? 'text-muted-foreground' : 'text-accent')} aria-hidden="true" />
          <div>
            <h3 className="text-base font-semibold">{t('home.defaults.contactTitle')}</h3>
            <p className="mt-1 text-sm text-muted-foreground">{t('home.defaults.contactDesc')}</p>
          </div>
          <Button type="button" variant="outline" size="sm" className="w-full">{t('home.defaults.contactAction')}</Button>
        </CardContent>
      </Card>
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
