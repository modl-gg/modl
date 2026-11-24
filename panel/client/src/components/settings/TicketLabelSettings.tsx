import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { ColorDot } from '@/components/ui/chip';
import { IconAction } from '@/components/ui/icon-action';
import { DEFAULT_LABEL_COLOR } from '@/components/ui/label-chip';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { TicketLabel } from '@/hooks/data/settings';
import { useConfirmation } from '@/hooks/use-confirmation';
import { optionalField } from '@/lib/defined-fields';
import { cn } from '@/lib/utils';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { newSettingsEntryId } from '@/lib/settings-ids';


const LABEL_COLOR_PRESETS = [
  '#d73a4a',
  '#e99695',
  '#0969da',
  '#1f6feb',
  '#8250df',
  '#a371f7',
  '#238636',
  '#3fb950',
  '#f9c513',
  '#d29922',
  '#6e7781',
  '#ffffff',
];

interface LabelDraft {
  name: string;
  color: string;
  description: string;
}

const EMPTY_LABEL_DRAFT: LabelDraft = { name: '', color: DEFAULT_LABEL_COLOR, description: '' };

function labelOf(id: string, draft: LabelDraft): TicketLabel {
  return {
    id,
    name: draft.name.trim(),
    color: draft.color,
    ...optionalField('description', draft.description.trim() || undefined),
  };
}

function draftOf(label: TicketLabel): LabelDraft {
  return { name: label.name, color: label.color, description: label.description ?? '' };
}

function LabelColorPicker({ color, onChange }: { color: string; onChange: (color: string) => void }) {
  const { t } = useTranslation();

  return (
    <div role="group" aria-label={t('settings.tickets.labelColor')} className="flex flex-wrap items-center gap-1.5">
      {LABEL_COLOR_PRESETS.map((preset) => (
        <button
          key={preset}
          type="button"
          aria-label={preset}
          aria-pressed={preset === color}
          className={cn(
            'h-6 w-6 rounded-full border focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
            preset === color && 'ring-2 ring-primary ring-offset-2 ring-offset-background',
          )}
          style={{ backgroundColor: preset }}
          onClick={() => {
            onChange(preset);
          }}
        />
      ))}
      <input
        type="color"
        value={color}
        aria-label={t('settings.tickets.customColor')}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        className="h-6 w-8 cursor-pointer rounded border bg-transparent"
      />
    </div>
  );
}

interface LabelEditor {
  label: TicketLabel | null;
}

interface LabelDialogProps {
  label: TicketLabel | null;
  onSubmit: (draft: LabelDraft) => void;
  onClose: () => void;
}

function LabelDialog({ label, onSubmit, onClose }: LabelDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const [draft, setDraft] = useState<LabelDraft>(() => (label === null ? EMPTY_LABEL_DRAFT : draftOf(label)));
  const valid = draft.name.trim() !== '';

  const update = (change: Partial<LabelDraft>) => {
    setDraft((current) => ({ ...current, ...change }));
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{label === null ? t('settings.tickets.addLabel') : t('settings.tickets.editLabel')}</DialogTitle>
        </DialogHeader>
        <form
          id={`${fieldId}-form`}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (valid) onSubmit(draft);
          }}
        >
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-name`}>{t('settings.tickets.name')}</Label>
            <Input
              id={`${fieldId}-name`}
              value={draft.name}
              placeholder={t('settings.tickets.labelNamePlaceholder')}
              onChange={(event) => {
                update({ name: event.target.value });
              }}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-description`}>{t('settings.tickets.descriptionOptional')}</Label>
            <Input
              id={`${fieldId}-description`}
              value={draft.description}
              onChange={(event) => {
                update({ description: event.target.value });
              }}
            />
          </div>
          <div className="space-y-2">
            <span className="text-sm font-medium">{t('settings.tickets.labelColor')}</span>
            <LabelColorPicker
              color={draft.color}
              onChange={(color) => {
                update({ color });
              }}
            />
          </div>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form={`${fieldId}-form`} disabled={!valid}>{t('common.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface TicketLabelSettingsProps {
  labels: TicketLabel[];
  onLabelsChange: (labels: TicketLabel[]) => void;
}

export default function TicketLabelSettings({ labels, onLabelsChange }: TicketLabelSettingsProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const [editor, setEditor] = useState<LabelEditor | null>(null);
  const confirmation = useConfirmation();

  const submit = (label: TicketLabel | null, draft: LabelDraft) => {
    onLabelsChange(label === null
      ? [...labels, labelOf(newSettingsEntryId('label'), draft)]
      : labels.map((current) => (current.id === label.id ? labelOf(label.id, draft) : current)));
    setEditor(null);
  };

  const deleteLabel = async (label: TicketLabel) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.tickets.deleteLabelTitle'),
      description: t('settings.tickets.deleteLabelConfirm', { name: label.name }),
      action: t('common.delete'),
      destructive: true,
    });
    if (confirmed) onLabelsChange(labels.filter((current) => current.id !== label.id));
  };

  return (
    <div>
      <SettingsPageAction>
        <SettingsPageActionButton icon={Plus} label={t('settings.tickets.addLabel')} onClick={() => { setEditor({ label: null }); }} />
      </SettingsPageAction>
      {labels.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.tickets.noLabels')}</p>
      ) : (
        <GroupRows>
          {labels.map((label) => (
            <GroupRow
              key={label.id}
              leading={<ColorDot color={label.color} className="h-3 w-3" />}
              title={label.name}
              description={label.description}
              actions={canModify && (
                <>
                  <IconAction icon={Pencil} label={t('settings.tickets.editLabel')} onClick={() => { setEditor({ label }); }} />
                  <IconAction icon={Trash2} label={t('settings.tickets.deleteLabelTitle')} destructive onClick={() => { void deleteLabel(label); }} />
                </>
              )}
            />
          ))}
        </GroupRows>
      )}

      {editor !== null && (
        <LabelDialog
          label={editor.label}
          onSubmit={(draft) => {
            submit(editor.label, draft);
          }}
          onClose={() => {
            setEditor(null);
          }}
        />
      )}

      {confirmation.dialog}
    </div>
  );
}
