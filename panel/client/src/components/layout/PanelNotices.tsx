import { AddressForwardingBanner } from '@/components/settings/ForwardingNoticeBanner';
import { LookupChoiceBanner } from '@/components/settings/LookupChoiceBanner';
import { UpdateBanner } from '@/components/updates/UpdateBanner';
import { PAGE_GUTTER_X } from './page-gutter';

export function PanelNotices() {
  return (
    <div className={`space-y-2 pt-4 empty:hidden ${PAGE_GUTTER_X}`}>
      <UpdateBanner />
      <AddressForwardingBanner />
      <LookupChoiceBanner />
    </div>
  );
}
