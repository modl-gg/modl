import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, RotateCcw, SquareArrowOutUpRight, Trash2, Upload, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useToast } from '@/hooks/use-toast';
import { usePublicThemeSettings, useSavePublicTheme, useUploadThemeBackground } from '@/hooks/data/public-theme';
import { apiErrorCode, describeError } from '@/lib/http-error';
import {
  DEFAULT_PUBLIC_THEME,
  DEFAULT_THEME_COLORS,
  PUBLIC_FONT_IDS,
  PUBLIC_RADIUS_IDS,
  PUBLIC_SCHEME_DEFAULTS,
  isHexColor,
  tripletToHex,
  type PublicFontId,
  type PublicRadiusId,
  type PublicSchemeDefault,
  type PublicTheme,
} from '@/lib/public-theme';
import { sameValue } from '@/lib/settings-save-model';
import { openExternalUrl } from '@/lib/utils';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { PublicThemePreview } from './PublicThemePreview';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

type ThemeColorKey = 'primaryColor' | 'accentColor' | 'backgroundColor';

const COLOR_FIELDS: readonly { key: ThemeColorKey; labelKey: string; fallback: string }[] = [
  { key: 'primaryColor', labelKey: 'settings.theme.primaryColor', fallback: tripletToHex(DEFAULT_THEME_COLORS.primary) },
  { key: 'accentColor', labelKey: 'settings.theme.accentColor', fallback: tripletToHex(DEFAULT_THEME_COLORS.accent) },
  { key: 'backgroundColor', labelKey: 'settings.theme.backgroundColor', fallback: tripletToHex(DEFAULT_THEME_COLORS.background) },
];

const FONT_LABEL_KEYS: Readonly<Record<PublicFontId, string>> = {
  system: 'settings.theme.fonts.system',
  inter: 'settings.theme.fonts.inter',
  nunito: 'settings.theme.fonts.nunito',
  lora: 'settings.theme.fonts.lora',
  'space-grotesk': 'settings.theme.fonts.spaceGrotesk',
};

const RADIUS_LABEL_KEYS: Readonly<Record<PublicRadiusId, string>> = {
  none: 'settings.theme.radii.none',
  small: 'settings.theme.radii.small',
  medium: 'settings.theme.radii.medium',
  large: 'settings.theme.radii.large',
};

const SCHEME_LABEL_KEYS: Readonly<Record<PublicSchemeDefault, string>> = {
  system: 'settings.theme.schemes.system',
  light: 'settings.theme.schemes.light',
  dark: 'settings.theme.schemes.dark',
};

const REJECTION_KEYS: Readonly<Record<string, string>> = {
  THEME_COLOR_INVALID: 'settings.theme.colorInvalid',
  THEME_BACKGROUND_IMAGE_INVALID: 'settings.theme.backgroundImageInvalid',
};

const IMAGE_TYPES = 'image/png,image/jpeg,image/gif,image/apng,image/webp';

function isColorDraft(value: string): boolean {
  return value === '' || isHexColor(value.trim());
}

function withColor(theme: PublicTheme, key: ThemeColorKey, value: string): PublicTheme {
  const next = { ...theme };
  next[key] = value;
  return next;
}

function isSavableTheme(theme: PublicTheme): boolean {
  return COLOR_FIELDS.every(({ key }) => isColorDraft(theme[key]));
}

interface ColorFieldProps {
  id: string;
  label: string;
  value: string;
  fallback: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

function ColorField({ id, label, value, fallback, disabled, onChange }: ColorFieldProps) {
  const { t } = useTranslation();
  const invalid = !isColorDraft(value);
  const errorId = `${id}-error`;
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      <div className="flex items-center gap-2">
        <input
          type="color"
          aria-label={label}
          value={isHexColor(value.trim()) ? value.trim().toLowerCase() : fallback}
          disabled={disabled}
          onChange={(event) => { onChange(event.target.value); }}
          className="h-10 w-12 shrink-0 cursor-pointer rounded-md border border-input bg-background p-1 disabled:cursor-not-allowed disabled:opacity-50"
        />
        <Input
          id={id}
          value={value}
          placeholder={fallback}
          spellCheck={false}
          maxLength={7}
          disabled={disabled}
          aria-invalid={invalid}
          aria-describedby={invalid ? errorId : undefined}
          onChange={(event) => { onChange(event.target.value); }}
          className={`${FIELD_WIDTH.number} font-mono`}
        />
        {value !== '' && !disabled && (
          <Button type="button" variant="ghost" size="icon" aria-label={t('settings.theme.useDefault')} onClick={() => { onChange(''); }}>
            <X className="h-4 w-4" aria-hidden="true" />
          </Button>
        )}
      </div>
      {invalid && <p id={errorId} className="text-sm text-destructive-text">{t('settings.theme.colorInvalid')}</p>}
    </div>
  );
}

interface BackgroundImageFieldProps {
  url: string;
  disabled: boolean;
  onChange: (url: string) => void;
}

function BackgroundImageField({ url, disabled, onChange }: BackgroundImageFieldProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadThemeBackground();
  const confirmation = useConfirmation();
  const label = t('settings.theme.backgroundImage');

  const uploadFile = async (file: File) => {
    try {
      onChange(await upload.mutateAsync(file));
    } catch (error) {
      toast({ title: t('settings.page.uploadFailed'), description: describeError(error, t('settings.page.uploadFailedDesc')), variant: 'destructive' });
    }
  };

  const confirmRemove = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.theme.removeImageTitle'),
      description: t('settings.theme.removeImageDescription'),
      action: t('common.remove'),
      destructive: true,
    });
    if (confirmed) onChange('');
  };

  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      <div className="flex items-center gap-3">
        <input
          ref={fileInputRef}
          type="file"
          accept={IMAGE_TYPES}
          className="hidden"
          aria-label={label}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) void uploadFile(file);
          }}
        />
        <div className="relative h-20 w-32 shrink-0 overflow-hidden rounded-lg border bg-surface-2">
          {url !== '' && <img src={url} alt={label} className="h-full w-full object-cover" />}
          {upload.isPending && (
            <span className="absolute inset-0 flex items-center justify-center bg-black/55">
              <Loader2 className="h-5 w-5 animate-spin text-white" aria-hidden="true" />
            </span>
          )}
        </div>
        <div className="flex flex-col gap-2">
          <Button type="button" variant="outline" size="icon" aria-label={t('common.upload')} disabled={disabled || upload.isPending} onClick={() => fileInputRef.current?.click()}>
            <Upload className="h-4 w-4" aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" size="icon" aria-label={t('common.remove')} disabled={disabled || url === '' || upload.isPending} onClick={() => { void confirmRemove(); }}>
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
      {confirmation.dialog}
    </div>
  );
}

interface ChoiceFieldProps<Choice extends string> {
  id: string;
  label: string;
  value: Choice;
  choices: readonly Choice[];
  choiceLabel: (choice: Choice) => string;
  disabled: boolean;
  onChange: (value: Choice) => void;
}

function ChoiceField<Choice extends string>({ id, label, value, choices, choiceLabel, disabled, onChange }: ChoiceFieldProps<Choice>) {
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>{label}</Label>
      <Select
        value={value}
        disabled={disabled}
        onValueChange={(next) => {
          const choice = choices.find((candidate) => candidate === next);
          if (choice !== undefined) onChange(choice);
        }}
      >
        <SelectTrigger id={id} className={FIELD_WIDTH.short}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {choices.map((choice) => <SelectItem key={choice} value={choice}>{choiceLabel(choice)}</SelectItem>)}
        </SelectContent>
      </Select>
    </div>
  );
}

const PublicThemeSettings = () => {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const settings = usePublicThemeSettings();
  const saveTheme = useSavePublicTheme();
  const confirmation = useConfirmation();
  const draft = useVersionedSettingsDraft({
    persisted: settings.data,
    fallback: DEFAULT_PUBLIC_THEME,
    save: async (write) => ({ saved: await saveTheme.mutateAsync(write) }),
    isValid: isSavableTheme,
    describeRejection: (error) => {
      const code = apiErrorCode(error);
      const key = code === null ? undefined : REJECTION_KEYS[code];
      return key === undefined ? null : t(key);
    },
  });

  if (settings.isPending) return <SettingsLoading />;
  if (settings.isError) {
    return (
      <div className="space-y-3">
        <Notice variant="error" announce>{describeError(settings.error, t('common.unknownError'))}</Notice>
        <Button type="button" variant="outline" size="sm" onClick={() => { void settings.refetch(); }}>{t('common.retry')}</Button>
      </div>
    );
  }

  const theme = draft.value;
  const disabled = !canModify;
  const change = (patch: Partial<PublicTheme>) => {
    draft.update((current) => ({ ...current, ...patch }));
  };

  const confirmReset = async () => {
    const confirmed = await confirmation.confirm({
      title: t('settings.theme.resetTitle'),
      description: t('settings.theme.resetDescription'),
      action: t('common.reset'),
      destructive: true,
    });
    if (confirmed) draft.update(DEFAULT_PUBLIC_THEME);
  };

  return (
    <>
      <SettingsPageAction>
        <SettingsPageActionButton icon={SquareArrowOutUpRight} label={t('settings.homepage.openHomepage')} onClick={() => { openExternalUrl('/'); }} />
        <SettingsPageActionButton
          icon={RotateCcw}
          label={t('settings.theme.resetDefaults')}
          disabled={sameValue(theme, DEFAULT_PUBLIC_THEME)}
          onClick={() => { void confirmReset(); }}
        />
      </SettingsPageAction>
      <SettingsSections>
        <SettingsSection>
          <PublicThemePreview theme={theme} />
        </SettingsSection>
        <SettingsSection>
          <div className="grid gap-6 md:grid-cols-3">
            {COLOR_FIELDS.map(({ key, labelKey, fallback }) => (
              <ColorField
                key={key}
                id={`public-theme-${key}`}
                label={t(labelKey)}
                value={theme[key]}
                fallback={fallback}
                disabled={disabled}
                onChange={(value) => { draft.update((current) => withColor(current, key, value)); }}
              />
            ))}
          </div>
          <BackgroundImageField url={theme.backgroundImageUrl} disabled={disabled} onChange={(backgroundImageUrl) => { change({ backgroundImageUrl }); }} />
        </SettingsSection>
        <SettingsSection>
          <ChoiceField
            id="public-theme-font"
            label={t('settings.theme.font')}
            value={theme.font}
            choices={PUBLIC_FONT_IDS}
            choiceLabel={(font) => t(FONT_LABEL_KEYS[font])}
            disabled={disabled}
            onChange={(font) => { change({ font }); }}
          />
          <ChoiceField
            id="public-theme-radius"
            label={t('settings.theme.radius')}
            value={theme.radius}
            choices={PUBLIC_RADIUS_IDS}
            choiceLabel={(radius) => t(RADIUS_LABEL_KEYS[radius])}
            disabled={disabled}
            onChange={(radius) => { change({ radius }); }}
          />
          <ChoiceField
            id="public-theme-scheme"
            label={t('settings.theme.colorScheme')}
            value={theme.colorScheme}
            choices={PUBLIC_SCHEME_DEFAULTS}
            choiceLabel={(scheme) => t(SCHEME_LABEL_KEYS[scheme])}
            disabled={disabled}
            onChange={(colorScheme) => { change({ colorScheme }); }}
          />
        </SettingsSection>
      </SettingsSections>
      {confirmation.dialog}
    </>
  );
};

export default PublicThemeSettings;
