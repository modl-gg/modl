import { useTranslation } from 'react-i18next';
import { SquareArrowOutUpRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { Textarea } from '@/components/ui/textarea';
import { usePublicCustomCss, useSavePublicCustomCss } from '@/hooks/data/public-theme';
import { apiErrorCode, describeError } from '@/lib/http-error';
import { openExternalUrl } from '@/lib/utils';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsLoading } from './layout/SettingsSections';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

const MAX_CSS_BYTES = 64 * 1024;
const TEXT_ENCODER = new TextEncoder();

const REJECTION_KEYS: Readonly<Record<string, string>> = {
  CUSTOM_CSS_TOO_LARGE: 'settings.customCss.tooLarge',
  CUSTOM_CSS_INVALID_CHARACTER: 'settings.customCss.invalidCharacter',
  CUSTOM_CSS_IMPORT: 'settings.customCss.import',
  CUSTOM_CSS_MARKUP: 'settings.customCss.markup',
  CUSTOM_CSS_SCRIPT: 'settings.customCss.script',
};

function fitsLimit(css: string): boolean {
  return TEXT_ENCODER.encode(css).length <= MAX_CSS_BYTES;
}

const CustomCssSettings = () => {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const stylesheet = usePublicCustomCss();
  const saveStylesheet = useSavePublicCustomCss();
  const draft = useVersionedSettingsDraft({
    persisted: stylesheet.data,
    fallback: '',
    save: async (write) => ({ saved: await saveStylesheet.mutateAsync(write) }),
    isValid: fitsLimit,
    describeRejection: (error) => {
      const code = apiErrorCode(error);
      const key = code === null ? undefined : REJECTION_KEYS[code];
      return key === undefined ? null : t(key);
    },
  });

  if (stylesheet.isPending) return <SettingsLoading />;
  if (stylesheet.isError) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{describeError(stylesheet.error, t('common.unknownError'))}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void stylesheet.refetch(); }}>{t('common.retry')}</Button>
      </div>
    );
  }

  const tooLarge = !fitsLimit(draft.value);

  return (
    <div className="space-y-2">
      <SettingsPageAction>
        <SettingsPageActionButton icon={SquareArrowOutUpRight} label={t('settings.homepage.openHomepage')} onClick={() => { openExternalUrl('/'); }} />
      </SettingsPageAction>
      <Textarea
        value={draft.value}
        onChange={(event) => { draft.update(event.target.value); }}
        aria-label={t('settings.pages.customCss')}
        aria-invalid={tooLarge}
        disabled={!canModify}
        spellCheck={false}
        autoCapitalize="off"
        autoComplete="off"
        autoCorrect="off"
        rows={24}
        className="min-h-[24rem] font-mono text-xs md:text-xs"
      />
      {tooLarge && <p className="text-sm text-destructive-text">{t('settings.customCss.tooLarge')}</p>}
    </div>
  );
};

export default CustomCssSettings;
