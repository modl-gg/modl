import { useEffect, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

const ALERT_DURATION_MS = 1200;
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
const SHAKE_KEYFRAMES: Keyframe[] = [
  { transform: 'translateX(0)' },
  { transform: 'translateX(-8px)' },
  { transform: 'translateX(8px)' },
  { transform: 'translateX(-6px)' },
  { transform: 'translateX(6px)' },
  { transform: 'translateX(-3px)' },
  { transform: 'translateX(0)' },
];
const SHAKE_TIMING: KeyframeAnimationOptions = { duration: 420, easing: 'ease-in-out' };

export interface SaveBarAlert {
  active: boolean;
  barRef: RefObject<HTMLDivElement | null>;
  raise: () => void;
}

function shake(element: HTMLElement | null): void {
  if (element === null || window.matchMedia(REDUCED_MOTION_QUERY).matches) return;
  element.animate(SHAKE_KEYFRAMES, SHAKE_TIMING);
}

export function useSaveBarAlert(): SaveBarAlert {
  const [active, setActive] = useState(false);
  const barRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timerRef.current !== null) clearTimeout(timerRef.current);
  }, []);

  const raise = () => {
    setActive(true);
    shake(barRef.current);
    if (timerRef.current !== null) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      setActive(false);
    }, ALERT_DURATION_MS);
  };

  return { active, barRef, raise };
}

export interface SaveBarExit {
  outcome: 'saved' | 'reset';
  leaving: boolean;
}

interface SaveBarProps {
  visible: boolean;
  canSave: boolean;
  saving: boolean;
  alert: SaveBarAlert;
  exit: SaveBarExit | null;
  onSave: () => void;
  onReset: () => void;
  onExited: () => void;
}

export function SaveBar({ visible, canSave, saving, alert, exit, onSave, onReset, onExited }: SaveBarProps) {
  const { t } = useTranslation();
  const exiting = visible ? null : exit;
  if (!visible && exiting === null) return null;
  const saved = exiting?.outcome === 'saved';
  const leaving = exiting?.leaving === true;

  return (
    <div className="sticky bottom-[calc(5rem+env(safe-area-inset-bottom))] z-20 mt-auto pt-6 md:bottom-[calc(1rem+var(--window-dock-height,0px))]">
      <div
        ref={alert.barRef}
        className={cn(
          'flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-lg border bg-popover px-4 py-3 text-popover-foreground shadow-lg transition-colors duration-200',
          leaving
            ? 'animate-out fade-out slide-out-to-bottom-4 fill-mode-forwards duration-200 ease-in'
            : 'animate-in fade-in slide-in-from-bottom-2',
          'motion-reduce:[animation-duration:1ms] motion-reduce:transition-none',
          alert.active && 'border-destructive-text bg-[color-mix(in_oklab,var(--color-destructive-text)_10%,var(--color-popover))]',
          saved && 'border-success/40 bg-[color-mix(in_oklab,var(--color-success)_10%,var(--color-popover))]',
        )}
        onAnimationEnd={(event) => {
          if (leaving && event.target === event.currentTarget) onExited();
        }}
      >
        {saved ? (
          <p role="status" className="flex h-8 items-center gap-2 text-sm font-medium text-success">
            <Check className="h-4 w-4 animate-in zoom-in-50 duration-200" aria-hidden="true" />
            {t('settings.saveBar.saved')}
          </p>
        ) : (
          <p className={cn('text-sm font-medium transition-colors duration-200', alert.active && 'text-destructive-text')}>{t('settings.saveBar.unsaved')}</p>
        )}
        {alert.active && <span role="alert" className="sr-only">{t('settings.saveBar.unsaved')}</span>}
        <div className={cn('flex items-center gap-2', exiting !== null && 'invisible')}>
          <Button type="button" variant="ghost" size="sm" disabled={saving} onClick={onReset}>
            {t('settings.saveBar.reset')}
          </Button>
          <Button type="button" size="sm" disabled={!canSave} onClick={onSave}>
            {saving && <Loader2 className="animate-spin" aria-hidden="true" />}
            {t('settings.saveBar.save')}
          </Button>
        </div>
      </div>
    </div>
  );
}
