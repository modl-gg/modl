import { useTranslation } from 'react-i18next';
import { CircleHelp } from 'lucide-react';
import { IconLink } from '@/components/ui/icon-action';
import { wikiUrl, type WikiLink } from '@/lib/wiki-links';

export function WikiHelpLink({ link }: { link: WikiLink }) {
  const { t } = useTranslation();
  return <IconLink icon={CircleHelp} label={t('common.help')} href={wikiUrl(link)} className="shrink-0" />;
}
