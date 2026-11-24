import { useTranslation } from 'react-i18next';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

export function AuditPeriodSelect({ value, onChange }: { value: string; onChange: (period: string) => void }) {
  const { t } = useTranslation();
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="w-32" aria-label={t('audit.timePeriod')}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="7d">{t('audit.period7d')}</SelectItem>
        <SelectItem value="30d">{t('audit.period30d')}</SelectItem>
        <SelectItem value="90d">{t('audit.period90d')}</SelectItem>
        <SelectItem value="all">{t('audit.periodAll')}</SelectItem>
      </SelectContent>
    </Select>
  );
}
