import type { IpLookupProviderId, KeyedIpLookupProviderId } from '@/hooks/data/player-addresses';

export const LOOKUP_PROVIDER_NAMES: Record<IpLookupProviderId, string> = {
  proxycheck: 'proxycheck.io',
  vpnapi: 'vpnapi.io',
  iphub: 'IPHub',
  ipapi_is: 'ipapi.is',
  proxycheck_nokey: 'proxycheck.io',
};

export const LOOKUP_PROVIDER_SIGN_UP: Record<KeyedIpLookupProviderId, string> = {
  proxycheck: 'https://proxycheck.io/dashboard/',
  vpnapi: 'https://vpnapi.io/signup',
  iphub: 'https://iphub.info/register',
  ipapi_is: 'https://ipapi.is/app/signup',
};
