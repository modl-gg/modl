import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getAvatarUrl } from '@/lib/api';

type AvatarState = 'loading' | 'loaded' | 'failed';

function initialsOf(username: string): string {
  return username.substring(0, 2) || '??';
}

export function PlayerAvatar({ playerId, username }: { playerId: string; username: string }) {
  const { t } = useTranslation();
  const [state, setState] = useState<AvatarState>('loading');
  const initials = <span className="text-2xl font-bold text-primary">{initialsOf(username)}</span>;

  return (
    <div className="relative h-16 w-16 bg-muted rounded-lg flex items-center justify-center overflow-hidden">
      {playerId !== '' && state !== 'failed' ? (
        <>
          <img
            src={getAvatarUrl(playerId, 64, true)}
            alt={t('player.avatarAlt', { player: username || t('player.unknownPlayer') })}
            className={`w-full h-full object-cover transition-opacity duration-200 ${state === 'loading' ? 'opacity-0' : 'opacity-100'}`}
            onError={() => setState('failed')}
            onLoad={() => setState('loaded')}
          />
          {state === 'loading' && (
            <div className="absolute inset-0 flex items-center justify-center">
              {initials}
            </div>
          )}
        </>
      ) : initials}
    </div>
  );
}
