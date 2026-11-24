import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import type { FormSection } from '@/types/forms';
import { newSettingsEntryId } from '@/lib/settings-ids';

interface FormSectionDialogProps {
  section: FormSection | null;
  onSubmit: (section: FormSection) => void;
  onClose: () => void;
}

export default function FormSectionDialog({ section, onSubmit, onClose }: FormSectionDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const [title, setTitle] = useState(section?.title ?? '');
  const [sectionDescription, setSectionDescription] = useState(section?.description ?? '');
  const [hideByDefault, setHideByDefault] = useState(section?.hideByDefault ?? false);

  const submit = () => {
    const trimmedTitle = title.trim();
    if (trimmedTitle === '') return;
    onSubmit({
      id: section?.id ?? newSettingsEntryId('section'),
      title: trimmedTitle,
      description: sectionDescription.trim() || undefined,
      order: section?.order ?? 0,
      hideByDefault,
    });
  };

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{section ? t('settings.tickets.editSection') : t('settings.tickets.addSection')}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-title`}>{t('settings.tickets.sectionTitle')}</Label>
            <Input
              id={`${fieldId}-title`}
              placeholder={t('settings.appealFormEditor.sectionTitlePlaceholder')}
              value={title}
              onChange={(event) => {
                setTitle(event.target.value);
              }}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-description`}>{t('settings.tickets.descriptionOptional')}</Label>
            <Input
              id={`${fieldId}-description`}
              placeholder={t('settings.appealFormEditor.sectionDescriptionPlaceholder')}
              value={sectionDescription}
              onChange={(event) => {
                setSectionDescription(event.target.value);
              }}
            />
          </div>

          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <Switch id={`${fieldId}-hidden`} checked={hideByDefault} onCheckedChange={setHideByDefault} />
              <Label htmlFor={`${fieldId}-hidden`}>{t('settings.appealFormEditor.hideByDefault')}</Label>
            </div>
            <p className="text-sm text-muted-foreground">{t('settings.tickets.hideByDefaultDesc')}</p>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={submit} disabled={title.trim() === ''}>
            {t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
