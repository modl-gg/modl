import {
  usePatchQuickResponses,
  usePatchTicketForms,
  usePatchTicketLabels,
  useQuickResponsesConfiguration,
  useTicketFormsDocument,
  useTicketLabels,
  type TicketFormsDocument,
  type TicketLabel,
} from '@/hooks/data/settings';
import { usePermissions } from '@/hooks/use-permissions';
import { defaultQuickResponsesConfig } from '@/types/quickResponses';
import type { TicketFormSettings } from '@/types/forms';
import { useVersionedSettingsDraft } from './save/use-settings-draft';

const EMPTY_TICKET_FORM: TicketFormSettings = { fields: [], sections: [] };

const EMPTY_TICKET_FORMS: TicketFormsDocument = {
  bug: EMPTY_TICKET_FORM,
  support: EMPTY_TICKET_FORM,
  application: EMPTY_TICKET_FORM,
};

const NO_LABELS: TicketLabel[] = [];

export function useQuickResponsesDraft() {
  const { canLoadSettingsData } = usePermissions();
  const query = useQuickResponsesConfiguration({ enabled: canLoadSettingsData('quickResponses') });
  const patch = usePatchQuickResponses();
  const draft = useVersionedSettingsDraft({ persisted: query.data, fallback: defaultQuickResponsesConfig, save: patch.mutateAsync });
  return { draft, isLoading: query.isPending };
}

export function useTicketLabelsDraft() {
  const { canLoadSettingsData } = usePermissions();
  const query = useTicketLabels({ enabled: canLoadSettingsData('ticketLabels') });
  const patch = usePatchTicketLabels();
  const draft = useVersionedSettingsDraft({ persisted: query.data, fallback: NO_LABELS, save: patch.mutateAsync });
  return { draft, isLoading: query.isPending };
}

export function useTicketFormsDraft() {
  const { canLoadSettingsData } = usePermissions();
  const query = useTicketFormsDocument({ enabled: canLoadSettingsData('ticketForms') });
  const patch = usePatchTicketForms();
  const draft = useVersionedSettingsDraft({ persisted: query.data, fallback: EMPTY_TICKET_FORMS, save: patch.mutateAsync });
  return { draft, isLoading: query.isPending };
}
