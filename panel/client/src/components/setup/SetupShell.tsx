import { useEffect, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BrandLogo } from '@/components/brand/BrandLogo';
import type { SetupScreen } from '@/lib/setup-steps';
import { SetupPosition } from './SetupPosition';
import { SETUP_TITLE_ID } from './setup-title';

interface SetupShellProps {
  screen: SetupScreen;
  title: string;
  intro: readonly string[];
  stepper: ReactNode;
  footer: ReactNode;
  children: ReactNode;
}

export function SetupShell({ screen, title, intro, stepper, footer, children }: SetupShellProps) {
  const { t } = useTranslation();
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  return (
    <div className="min-h-screen bg-background flex flex-col">
      <header className="border-b">
        <div className="mx-auto flex w-full max-w-5xl items-center px-4 py-3">
          <p className="flex items-center">
            <BrandLogo icon="panel" wordmarkClassName="text-3xl" imageClassName="h-9 w-auto object-contain" />
            <span className="sr-only">{t('pages.setup.title')}</span>
          </p>
        </div>
      </header>
      <div className="mx-auto w-full max-w-5xl flex-1 px-4 pt-6 md:grid md:grid-cols-[15rem_minmax(0,1fr)] md:gap-8">
        <div className="hidden md:block">{stepper}</div>
        <div className="flex min-w-0 flex-col">
          <SetupPosition screen={screen} />
          <h1 id={SETUP_TITLE_ID} ref={headingRef} tabIndex={-1} className="text-2xl font-semibold tracking-tight focus:outline-hidden">{title}</h1>
          <div className="mt-2 max-w-2xl space-y-3 text-sm text-muted-foreground">
            {intro.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}
          </div>
          <div className="mt-6 flex-1">{children}</div>
          {footer !== null && <div className="sticky bottom-0 -mx-4 mt-8 border-t bg-background/95 px-4 py-3 backdrop-blur">{footer}</div>}
        </div>
      </div>
    </div>
  );
}
