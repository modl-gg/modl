import { File, FileText, Image as ImageIcon, Video } from 'lucide-react';

interface TicketFileIconProps {
  fileType: string;
  className?: string;
}

export function TicketFileIcon({ fileType, className = 'h-3 w-3' }: TicketFileIconProps) {
  if (fileType.startsWith('image/')) return <ImageIcon className={className} />;
  if (fileType.startsWith('video/')) return <Video className={className} />;
  if (fileType === 'application/pdf') return <FileText className={className} />;
  return <File className={className} />;
}
