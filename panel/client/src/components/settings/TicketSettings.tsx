import QuickResponseSettings from './QuickResponseSettings';
import TicketFormsEditor from './TicketFormsEditor';
import TicketLabelSettings from './TicketLabelSettings';
import { SettingsLoading } from './layout/SettingsSections';
import { useQuickResponsesDraft, useTicketFormsDraft, useTicketLabelsDraft } from './use-ticket-settings-state';

export function QuickRepliesSettings() {
  const { draft, isLoading } = useQuickResponsesDraft();
  if (isLoading) return <SettingsLoading />;
  return <QuickResponseSettings quickResponses={draft.value} onQuickResponsesChange={draft.update} />;
}

export function TicketLabelsSettings() {
  const { draft, isLoading } = useTicketLabelsDraft();
  if (isLoading) return <SettingsLoading />;
  return <TicketLabelSettings labels={draft.value} onLabelsChange={draft.update} />;
}

export function TicketFormsSettings() {
  const { draft, isLoading } = useTicketFormsDraft();
  if (isLoading) return <SettingsLoading />;
  return (
    <TicketFormsEditor
      ticketForms={draft.value}
      onTicketFormsChange={(update) => {
        draft.update((document) => ({ ...document, ...update(document) }));
      }}
    />
  );
}
