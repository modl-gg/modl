import { useTranslation } from 'react-i18next';
import { openExternalUrl, safeExternalHref } from '@/lib/utils';
import { getTrustedEvidenceMediaType, type MediaTrustPolicy } from '@/utils/evidence-utils';
import { formatDateWithTime } from '@/utils/date-utils';
import { useMediaTrustPolicy } from '@/hooks/use-media-upload';
import { keyByContent } from '@/components/ui/stable-keys';

export interface EvidenceEntry {
  text?: string | undefined;
  type?: string | undefined;
  url?: string | undefined;
  uploadedBy?: string | undefined;
  uploadedById?: string | undefined;
  uploadedAt?: string | undefined;
  issuerName?: string;
  date?: string;
  fileUrl?: string;
  fileName?: string | undefined;
  fileType?: string | undefined;
  fileSize?: number | undefined;
}

export type EvidenceValue = string | EvidenceEntry;

export interface EvidenceListProps {
  evidence?: EvidenceValue[];
  className?: string;
}

interface NormalizedEvidence {
  text: string;
  issuer: string | null;
  date: string | null;
  type: string;
  fileUrl: string;
  fileName: string;
}

function normalizeEvidence(item: EvidenceValue): NormalizedEvidence {
  if (typeof item === 'string') {
    return {
      text: item,
      issuer: null,
      date: null,
      type: (/^https?:\/\//.exec(item)) ? 'url' : 'text',
      fileUrl: '',
      fileName: '',
    };
  }

  const issuer = item.uploadedBy || item.issuerName || null;
  const dateValue = item.uploadedAt || item.date;
  const date = dateValue ? formatDateWithTime(dateValue) : null;
  const type = item.type || 'text';
  const fileUrl = item.url || item.fileUrl || '';
  const fileName = item.fileName || '';
  const text = item.text || (fileUrl && type === 'url' ? fileUrl : '');

  return {
    text,
    issuer,
    date,
    type,
    fileUrl,
    fileName,
  };
}

function EvidenceListItem({ item, mediaTrustPolicy }: { item: EvidenceValue; mediaTrustPolicy: MediaTrustPolicy }) {
  const { t } = useTranslation();
  const { text, issuer, date, type, fileUrl, fileName } = normalizeEvidence(item);
  const issuerInfo = t('evidence.issuedBy', {
    issuer: issuer ?? t('evidence.system'),
    date: date ?? t('common.unknown'),
  });

  const detectionUrl = type === 'file' ? fileUrl : text;
  const mediaType = type === 'url' || type === 'file' ? getTrustedEvidenceMediaType(detectionUrl, mediaTrustPolicy) : 'text';
  const fileHref = safeExternalHref(fileUrl);
  const evidenceHref = safeExternalHref(text);

  return (
    <li className="text-sm">
      <div>
        <div className="min-w-0">
          {type === 'file' ? (
            <div className="space-y-2">
              <a
                href={fileHref}
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary hover:underline break-all"
              >
                {fileName || t('evidence.unknownFile')}
              </a>
              {text && text !== fileName && (
                <div className="text-muted-foreground">{text}</div>
              )}
              {mediaType === 'image' && (
                <img
                  src={fileHref}
                  alt={t('punishment.evidence')}
                  className="max-w-full max-h-48 rounded border cursor-pointer"
                  style={{ maxWidth: '300px' }}
                  onClick={() => openExternalUrl(fileUrl)}
                />
              )}
              {mediaType === 'video' && (
                <video
                  src={fileHref}
                  controls
                  className="max-w-full max-h-48 rounded border"
                  style={{ maxWidth: '300px' }}
                />
              )}
              {mediaType === 'link' && (
                <a
                  href={fileHref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-primary hover:underline break-all"
                >
                  {t('upload.downloadFile')}
                </a>
              )}
            </div>
          ) : mediaType === 'image' ? (
            <img
              src={evidenceHref}
              alt={t('punishment.evidence')}
              className="max-w-full max-h-48 rounded border"
              style={{ maxWidth: '300px' }}
            />
          ) : mediaType === 'video' ? (
            <video
              src={evidenceHref}
              controls
              className="max-w-full max-h-48 rounded border"
              style={{ maxWidth: '300px' }}
            />
          ) : type === 'url' ? (
            <a
              href={evidenceHref}
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary hover:underline break-all"
            >
              {text}
            </a>
          ) : (
            <span className="break-all">{text}</span>
          )}
          <p className="text-muted-foreground text-xs mt-0.5">{issuerInfo}</p>
        </div>
      </div>
    </li>
  );
}

function evidenceContentKey(item: EvidenceValue): string {
  if (typeof item === 'string') {
    return item;
  }
  return [item.url, item.fileUrl, item.text, item.uploadedAt, item.date].map((part) => part ?? '').join('|');
}

export function EvidenceList({ evidence, className }: EvidenceListProps) {
  const { t } = useTranslation();
  const mediaTrustPolicy = useMediaTrustPolicy();

  return (
    <div className={className}>
      <p className="mb-1 text-xs font-medium text-muted-foreground">{t('punishment.evidence')}</p>
      {evidence && evidence.length > 0 ? (
        <ul className="space-y-2">
          {keyByContent(evidence, evidenceContentKey).map(({ key, item }) => (
            <EvidenceListItem key={key} item={item} mediaTrustPolicy={mediaTrustPolicy} />
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">{t('player.noEvidence')}</p>
      )}
    </div>
  );
}
