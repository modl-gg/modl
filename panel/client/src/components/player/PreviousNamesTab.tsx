import { useTranslation } from 'react-i18next';
import { keyByContent } from '@/components/ui/stable-keys';
import { ProfileEmpty } from './ProfileEmpty';
import type { PlayerUsernameRecord } from './player-profile';

export function PreviousNamesTab({ names }: { readonly names: readonly PlayerUsernameRecord[] }) {
  const { t } = useTranslation();
  return (
    names.length > 0 ? (
      <ul className="divide-y">
        {keyByContent(names, (name) => `${name.username}:${name.date}`).map(({ key, item: name }) => (
          <li key={key} className="py-2 text-sm">{name.username}</li>
        ))}
      </ul>
    ) : (
      <ProfileEmpty>{t('player.noPreviousNames')}</ProfileEmpty>
    )
  );
}
