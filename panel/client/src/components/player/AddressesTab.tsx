import { useTranslation } from 'react-i18next';
import { StatusBadge } from '@/components/ui/status-badge';
import { keyByContent } from '@/components/ui/stable-keys';
import { ProfileEmpty } from './ProfileEmpty';
import type { VpnStatusId } from '@/hooks/data/players';
import { formatDateWithTime } from '@/utils/date-utils';
import type { PlayerAddressRecord } from './player-profile';

const VPN_STATUS_LABELS: Record<VpnStatusId, string> = {
  yes: 'player.addresses.vpnYes',
  no: 'player.addresses.vpnNo',
  unknown: 'player.addresses.vpnUnknown',
};

const VPN_STATUS_INTENTS: Record<VpnStatusId, 'destructive' | 'neutral'> = {
  yes: 'destructive',
  no: 'neutral',
  unknown: 'neutral',
};

function locationOf(address: PlayerAddressRecord): string | null {
  const parts = [address.region, address.country].filter((part): part is string => part !== undefined && part !== '');
  return parts.length === 0 ? null : parts.join(', ');
}

function AddressRow({ address }: { readonly address: PlayerAddressRecord }) {
  const { t } = useTranslation();
  return (
    <li className="space-y-1 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{locationOf(address) ?? t('player.addresses.locationUnknown')}</span>
        <StatusBadge intent={VPN_STATUS_INTENTS[address.vpnStatus]}>
          {t('player.addresses.vpn', { status: t(VPN_STATUS_LABELS[address.vpnStatus]) })}
        </StatusBadge>
        {address.hosting && <StatusBadge intent="warning">{t('player.addresses.hosting')}</StatusBadge>}
      </div>
      {address.asn !== undefined && address.asn !== '' && <p className="text-xs text-muted-foreground">{address.asn}</p>}
      <p className="text-xs text-muted-foreground">
        {t('player.addresses.seen', {
          first: formatDateWithTime(address.firstLogin),
          last: formatDateWithTime(address.lastLogin || address.firstLogin),
          logins: address.loginCount,
        })}
      </p>
    </li>
  );
}

export function AddressesTab({ addresses }: { readonly addresses: readonly PlayerAddressRecord[] }) {
  const { t } = useTranslation();
  return (
    addresses.length === 0 ? (
      <ProfileEmpty>{t('player.addresses.none')}</ProfileEmpty>
    ) : (
      <ul className="divide-y">
        {keyByContent(addresses, (address) => `${address.firstLogin}:${address.lastLogin}:${address.asn ?? ''}`).map(({ key, item }) => (
          <AddressRow key={key} address={item} />
        ))}
      </ul>
    )
  );
}
