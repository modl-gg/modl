import { useUpdateProfile, type ProfileChanges } from '@/hooks/data/account';
import { useAuth } from '@/hooks/use-auth';
import { resolveLanguage } from '@/hooks/use-language-preference';
import { usePublicSettings } from '@/hooks/use-public-settings';
import { DEFAULT_DATE_FORMAT } from '@/utils/date-utils';
import { useSettingsDraft } from './save/use-settings-draft';

export interface ProfilePreferences {
  username: string;
  language: string;
  dateFormat: string;
}

const NO_PREFERENCES: ProfilePreferences = { username: '', language: '', dateFormat: DEFAULT_DATE_FORMAT };

function changedPreferences(next: ProfilePreferences, saved: ProfilePreferences): ProfileChanges {
  const changes: ProfileChanges = {};
  if (next.username !== saved.username) changes.username = next.username.trim();
  if (next.language !== saved.language) changes.language = next.language;
  if (next.dateFormat !== saved.dateFormat) changes.dateFormat = next.dateFormat;
  return changes;
}

function samePreferences(preferences: ProfilePreferences): ProfilePreferences {
  return preferences;
}

export function useProfilePreferencesDraft() {
  const { user } = useAuth();
  const { data: publicSettings } = usePublicSettings();
  const updateProfile = useUpdateProfile();
  const loaded: ProfilePreferences | undefined = user === null ? undefined : {
    username: user.username,
    language: resolveLanguage(user.language, publicSettings?.defaultLanguage),
    dateFormat: user.dateFormat,
  };

  return useSettingsDraft({
    snapshot: loaded,
    fallback: NO_PREFERENCES,
    valueOf: samePreferences,
    isValid: (preferences) => preferences.username.trim() !== '',
    save: async (preferences, saved) => {
      await updateProfile.mutateAsync(changedPreferences(preferences, saved));
      return { ...preferences, username: preferences.username.trim() };
    },
  });
}
