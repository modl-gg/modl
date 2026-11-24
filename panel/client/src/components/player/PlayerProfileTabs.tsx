import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import PlayerReplaysList from '@/components/player-replays-list';
import type { PunishmentFocus } from '@/lib/player-links';
import type { PlayerProfile } from './player-profile';
import type { PlayerProfileState } from './use-player-profile';
import { PunishmentHistoryTab } from './history/PunishmentHistoryTab';
import { LinkedAccountsTab } from './LinkedAccountsTab';
import { PlayerNotesTab } from './PlayerNotesTab';
import { PlayerTicketsTab } from './PlayerTicketsTab';
import { PreviousNamesTab } from './PreviousNamesTab';
import { AddressesTab } from './AddressesTab';
import { PlayerPunishTab } from './PlayerPunishTab';

const PROFILE_TABS = [
  { value: 'history', labelKey: 'player.tabs.history' },
  { value: 'linked', labelKey: 'player.tabs.connected' },
  { value: 'notes', labelKey: 'player.tabs.notes' },
  { value: 'tickets', labelKey: 'player.tabs.tickets' },
  { value: 'replays', labelKey: 'player.tabs.replays' },
  { value: 'names', labelKey: 'player.tabs.names' },
  { value: 'addresses', labelKey: 'player.tabs.addresses' },
  { value: 'punishment', labelKey: 'player.tabs.punish' },
] as const;

type ProfileTab = (typeof PROFILE_TABS)[number]['value'];

export type ProfileTabSelector = 'list' | 'select';

interface PlayerProfileTabsProps {
  readonly state: PlayerProfileState;
  readonly profile: PlayerProfile;
  readonly tabSelector: ProfileTabSelector;
  readonly focus?: PunishmentFocus | null | undefined;
  readonly onOpenPlayer: (uuid: string, username: string) => void;
}

function profileTabOf(value: string): ProfileTab {
  return PROFILE_TABS.find((tab) => tab.value === value)?.value ?? 'history';
}

function TabSelector({ selector, value, onChange }: { readonly selector: ProfileTabSelector; readonly value: ProfileTab; readonly onChange: (value: string) => void }) {
  const { t } = useTranslation();
  if (selector === 'select') {
    return (
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger className="w-full" aria-label={t('player.tabs.label')}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {PROFILE_TABS.map((tab) => (
            <SelectItem key={tab.value} value={tab.value}>{t(tab.labelKey)}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    );
  }
  return (
    <TabsList className="grid w-full grid-cols-8">
      {PROFILE_TABS.map((tab) => (
        <TabsTrigger key={tab.value} value={tab.value} className="px-2 text-xs">
          {t(tab.labelKey)}
        </TabsTrigger>
      ))}
    </TabsList>
  );
}

function ProfileTabContent({ value, keepMounted = false, children }: { readonly value: ProfileTab; readonly keepMounted?: boolean; readonly children: ReactNode }) {
  return (
    <TabsContent value={value} forceMount={keepMounted ? true : undefined} className="mt-4 space-y-3 data-[state=inactive]:hidden">
      {children}
    </TabsContent>
  );
}

export function PlayerProfileTabs({ state, profile, tabSelector, focus = null, onOpenPlayer }: PlayerProfileTabsProps) {
  const [activeTab, setActiveTab] = useState<ProfileTab>('history');
  const [shownFocus, setShownFocus] = useState<PunishmentFocus | null>(null);
  if (focus !== shownFocus) {
    setShownFocus(focus);
    if (focus !== null) setActiveTab('history');
  }
  const selectTab = (value: string) => setActiveTab(profileTabOf(value));
  const { playerId } = state;

  return (
    <Tabs value={activeTab} className="w-full" onValueChange={selectTab}>
      <TabSelector selector={tabSelector} value={activeTab} onChange={selectTab} />
      <ProfileTabContent value="history" keepMounted>
        <PunishmentHistoryTab
          playerId={playerId}
          history={profile.history}
          tickets={state.tickets}
          focus={focus}
          onOpenPlayer={onOpenPlayer}
        />
      </ProfileTabContent>
      <ProfileTabContent value="linked">
        <LinkedAccountsTab
          linkedAccounts={profile.linkedAccounts}
          isLoading={state.isLoadingLinkedAccounts}
          isSearching={state.isSearchingLinkedAccounts}
          searchFailed={state.linkedAccountSearchFailed}
          onRetrySearch={state.refreshLinkedAccounts}
          onOpenPlayer={onOpenPlayer}
        />
      </ProfileTabContent>
      <ProfileTabContent value="notes" keepMounted>
        <PlayerNotesTab playerId={playerId} notes={profile.notes} />
      </ProfileTabContent>
      <ProfileTabContent value="tickets">
        <PlayerTicketsTab playerId={playerId} tickets={state.tickets} isLoading={state.isLoadingTickets} />
      </ProfileTabContent>
      <ProfileTabContent value="replays">
        <PlayerReplaysList playerId={playerId} />
      </ProfileTabContent>
      <ProfileTabContent value="names">
        <PreviousNamesTab names={profile.previousNames} />
      </ProfileTabContent>
      <ProfileTabContent value="addresses">
        <AddressesTab addresses={profile.addresses} />
      </ProfileTabContent>
      <ProfileTabContent value="punishment" keepMounted>
        <PlayerPunishTab
          playerId={playerId}
          profile={profile}
          tickets={state.tickets}
          punishmentTypes={state.punishmentTypes}
          permittedTypesByCategory={state.permittedTypesByCategory}
          isLoadingPunishmentTypes={state.isLoadingPunishmentTypes}
        />
      </ProfileTabContent>
    </Tabs>
  );
}
