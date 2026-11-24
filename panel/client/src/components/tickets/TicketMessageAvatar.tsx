import { memo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { getAvatarUrl } from '@/lib/api';
import type { StaffMember } from '@/hooks/data/staff';
import type { TicketMessageView } from './ticket-view';

const AVATAR_SIZE = 32;

interface AvatarFallbackProps {
  initials: string;
  className: string;
  textClassName: string;
}

function AvatarFallback({ initials, className, textClassName }: AvatarFallbackProps) {
  return (
    <div className={`h-8 w-8 rounded-md flex items-center justify-center shrink-0 ${className}`}>
      <span className={`text-xs font-bold ${textClassName}`}>{initials}</span>
    </div>
  );
}

interface AvatarImageProps {
  src: string;
  alt: string;
  initials: string;
  fallback: AvatarFallbackProps;
}

function AvatarImage({ src, alt, initials, fallback }: AvatarImageProps) {
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);

  if (failed) {
    return <AvatarFallback {...fallback} />;
  }

  return (
    <div className="relative h-8 w-8 bg-muted rounded-md flex items-center justify-center overflow-hidden shrink-0">
      <img
        src={src}
        alt={alt}
        className={`w-full h-full object-cover transition-opacity duration-200 ${loading ? 'opacity-0' : 'opacity-100'}`}
        onError={() => {
          setFailed(true);
          setLoading(false);
        }}
        onLoad={() => {
          setLoading(false);
        }}
      />
      {loading && (
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-xs font-bold text-primary">{initials}</span>
        </div>
      )}
    </div>
  );
}

interface TicketMessageAvatarProps {
  message: TicketMessageView;
  creatorUuid: string;
  staff: readonly StaffMember[] | undefined;
}

function staffAvatarUrl(message: TicketMessageView, staff: readonly StaffMember[] | undefined): string {
  if (message.avatar) return message.avatar;
  const minecraftUuid = staff?.find((member) => member.username === message.sender)?.assignedMinecraftUuid;
  return minecraftUuid ? getAvatarUrl(minecraftUuid, AVATAR_SIZE, true) : '';
}

function TicketMessageAvatarView({ message, creatorUuid, staff }: TicketMessageAvatarProps) {
  const { t } = useTranslation();
  const alt = t('ticket.avatarAlt', { name: message.sender });

  if (message.senderType === 'user') {
    const initials = message.sender.substring(0, 2) || t('ticket.initials.user');
    const fallback = { initials, className: 'bg-blue-100', textClassName: 'text-blue-600' };
    return creatorUuid
      ? <AvatarImage key={creatorUuid} src={getAvatarUrl(creatorUuid, AVATAR_SIZE, true)} alt={alt} initials={initials} fallback={fallback} />
      : <AvatarFallback {...fallback} />;
  }

  if (message.senderType === 'staff' || message.staff) {
    const initials = message.sender.substring(0, 2) || t('ticket.initials.staff');
    const fallback = { initials, className: 'bg-green-100', textClassName: 'text-green-600' };
    const avatarUrl = staffAvatarUrl(message, staff);
    return avatarUrl
      ? <AvatarImage key={avatarUrl} src={avatarUrl} alt={alt} initials={initials} fallback={fallback} />
      : <AvatarFallback {...fallback} />;
  }

  return <AvatarFallback initials={t('ticket.initials.system')} className="bg-gray-100" textClassName="text-gray-600" />;
}

export const TicketMessageAvatar = memo(TicketMessageAvatarView);
