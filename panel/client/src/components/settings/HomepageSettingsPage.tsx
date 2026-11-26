import { useId, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { SquareArrowOutUpRight } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { LoadFailure } from '@/components/ui/load-failure';
import { Switch } from '@/components/ui/switch';
import {
  DEFAULT_HOMEPAGE_SETTINGS,
  MAX_FOOTER_LINKS,
  MAX_HOMEPAGE_CARDS,
  MAX_SERVER_ADDRESS_LENGTH,
  MAX_TAGLINE_LENGTH,
  useHomepageSettings,
  useSaveHomepageSettings,
  type HomepageSettings,
} from '@/hooks/data/homepage-settings';
import type { VersionedSettings } from '@/hooks/data/settings';
import { apiErrorCode, describeError } from '@/lib/http-error';
import { openExternalUrl } from '@/lib/utils';
import { editableFooterLinks, FooterLinkSettings, isFooterLinkValid, savedFooterLinks, type EditableFooterLink } from './FooterLinkSettings';
import { editableHomepageCards, HomepageCardList, isHomepageCardValid, savedHomepageCards, type EditableHomepageCard } from './HomepageCardSettings';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { FIELD_WIDTH, SettingsLoading, SettingsSection, SettingsSections } from './layout/SettingsSections';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

const WHITESPACE = /\s/g;

const REJECTION_KEYS: Readonly<Record<string, string>> = {
  HOMEPAGE_TEXT_INVALID: 'settings.homepage.textInvalid',
  HOMEPAGE_CARD_INVALID: 'settings.homepage.cardInvalid',
  HOMEPAGE_LINK_URL_INVALID: 'settings.homepage.linkUrlInvalid',
  HOMEPAGE_TOO_MANY_LINKS: 'settings.homepage.tooManyLinks',
};

interface HomepageDraft extends Omit<HomepageSettings, 'footerLinks' | 'cards'> {
  footerLinks: EditableFooterLink[];
  cards: EditableHomepageCard[];
}

const EMPTY_DRAFT: HomepageDraft = { ...DEFAULT_HOMEPAGE_SETTINGS, footerLinks: [], cards: [] };

function toDraft(settings: VersionedSettings<HomepageSettings>): VersionedSettings<HomepageDraft> {
  return {
    version: settings.version,
    value: {
      ...settings.value,
      footerLinks: editableFooterLinks(settings.value.footerLinks),
      cards: editableHomepageCards(settings.value.cards),
    },
  };
}

function fromDraft(draft: HomepageDraft): HomepageSettings {
  return { ...draft, footerLinks: savedFooterLinks(draft.footerLinks), cards: savedHomepageCards(draft.cards) };
}

function isSavable(settings: HomepageDraft): boolean {
  return settings.tagline.trim().length <= MAX_TAGLINE_LENGTH
    && settings.serverAddress.length <= MAX_SERVER_ADDRESS_LENGTH
    && settings.footerLinks.length <= MAX_FOOTER_LINKS
    && settings.footerLinks.every(isFooterLinkValid)
    && settings.cards.length <= MAX_HOMEPAGE_CARDS
    && settings.cards.every(isHomepageCardValid);
}

interface HeaderFieldsProps {
  settings: HomepageDraft;
  disabled: boolean;
  onChange: (patch: Partial<HomepageDraft>) => void;
}

function HeaderFields({ settings, disabled, onChange }: HeaderFieldsProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  return (
    <SettingsSection title={t('settings.homepage.header')}>
      <div className="space-y-2">
        <Label htmlFor={`${fieldId}-tagline`}>{t('settings.homepage.tagline')}</Label>
        <Input
          id={`${fieldId}-tagline`}
          className={FIELD_WIDTH.wide}
          value={settings.tagline}
          maxLength={MAX_TAGLINE_LENGTH}
          disabled={disabled}
          onChange={(event) => { onChange({ tagline: event.target.value }); }}
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor={`${fieldId}-address`}>{t('settings.homepage.serverAddress')}</Label>
        <Input
          id={`${fieldId}-address`}
          className={`${FIELD_WIDTH.text} font-mono`}
          value={settings.serverAddress}
          placeholder={t('settings.homepage.serverAddressPlaceholder')}
          maxLength={MAX_SERVER_ADDRESS_LENGTH}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          disabled={disabled}
          onChange={(event) => { onChange({ serverAddress: event.target.value.replace(WHITESPACE, '') }); }}
        />
      </div>
      <div className="flex items-center gap-3">
        <Switch
          id={`${fieldId}-players`}
          checked={settings.showPlayerCount}
          disabled={disabled}
          onCheckedChange={(showPlayerCount) => { onChange({ showPlayerCount }); }}
        />
        <Label htmlFor={`${fieldId}-players`}>{t('settings.homepage.showPlayerCount')}</Label>
      </div>
    </SettingsSection>
  );
}

export default function HomepageSettingsPage() {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const settings = useHomepageSettings();
  const saveSettings = useSaveHomepageSettings();
  const persisted = useMemo(() => (settings.data === undefined ? undefined : toDraft(settings.data)), [settings.data]);
  const draft = useVersionedSettingsDraft({
    persisted,
    fallback: EMPTY_DRAFT,
    save: async ({ expectedVersion, value }) => ({
      saved: toDraft(await saveSettings.mutateAsync({ expectedVersion, value: fromDraft(value) })),
    }),
    isValid: isSavable,
    describeRejection: (error) => {
      const code = apiErrorCode(error);
      const key = code === null ? undefined : REJECTION_KEYS[code];
      return key === undefined ? null : t(key);
    },
  });

  if (settings.isPending) return <SettingsLoading />;
  if (settings.isError) {
    return <LoadFailure message={describeError(settings.error, t('common.unknownError'))} onRetry={() => { void settings.refetch(); }} />;
  }

  const change = (patch: Partial<HomepageDraft>) => {
    draft.update((current) => ({ ...current, ...patch }));
  };

  return (
    <SettingsDndProvider>
      <SettingsPageAction requiresModify={false}>
        <SettingsPageActionButton icon={SquareArrowOutUpRight} label={t('settings.homepage.openHomepage')} onClick={() => { openExternalUrl('/'); }} />
      </SettingsPageAction>
      <SettingsSections>
        <HeaderFields settings={draft.value} disabled={!canModify} onChange={change} />
        <HomepageCardList
          cards={draft.value.cards}
          canModify={canModify}
          onChange={(update) => { draft.update((current) => ({ ...current, cards: update(current.cards) })); }}
        />
        <FooterLinkSettings
          links={draft.value.footerLinks}
          canModify={canModify}
          onChange={(update) => { draft.update((current) => ({ ...current, footerLinks: update(current.footerLinks) })); }}
        />
      </SettingsSections>
    </SettingsDndProvider>
  );
}
