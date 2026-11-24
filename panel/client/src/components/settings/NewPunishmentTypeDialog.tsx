import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PUNISHMENT_CATEGORY_LABEL_KEYS } from '@/lib/punishment-category-labels';
import { Loader2 } from 'lucide-react';
import type { CustomPunishmentCategory } from '@/hooks/data/punishment-types';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

const CATEGORY_LABEL_KEYS: Record<CustomPunishmentCategory, string> = {
  Gameplay: PUNISHMENT_CATEGORY_LABEL_KEYS.gameplay,
  Social: PUNISHMENT_CATEGORY_LABEL_KEYS.social,
};

const CATEGORIES: readonly CustomPunishmentCategory[] = ['Gameplay', 'Social'];

function isCategory(value: string): value is CustomPunishmentCategory {
  return CATEGORIES.some((category) => category === value);
}

interface NewPunishmentTypeDialogProps {
  isSaving: boolean;
  onSubmit: (name: string, category: CustomPunishmentCategory) => void;
  onClose: () => void;
}

export default function NewPunishmentTypeDialog({ isSaving, onSubmit, onClose }: NewPunishmentTypeDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const [name, setName] = useState('');
  const [category, setCategory] = useState<CustomPunishmentCategory>('Gameplay');
  const trimmedName = name.trim();

  return (
    <Dialog open onOpenChange={(open) => {
      if (!open) onClose();
    }}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{t('settings.punishment.addNewType')}</DialogTitle>
        </DialogHeader>
        <form
          id={`${fieldId}-form`}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (trimmedName !== '') onSubmit(trimmedName, category);
          }}
        >
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-name`}>{t('settings.punishment.punishmentName')}</Label>
            <Input
              id={`${fieldId}-name`}
              placeholder={t('settings.punishment.punishmentNamePlaceholder')}
              value={name}
              onChange={(event) => {
                setName(event.target.value);
              }}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-category`}>{t('settings.punishment.category')}</Label>
            <Select
              value={category}
              onValueChange={(value) => {
                if (isCategory(value)) setCategory(value);
              }}
            >
              <SelectTrigger id={`${fieldId}-category`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {CATEGORIES.map((option) => (
                  <SelectItem key={option} value={option}>{t(CATEGORY_LABEL_KEYS[option])}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form={`${fieldId}-form`} disabled={trimmedName === '' || isSaving}>
            {isSaving && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('settings.punishment.addType')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
