import { useIsMutating } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { INVITE_STAFF_MUTATION_KEY, type InviteLink } from '@/hooks/data/staff';
import { InviteStaffForm, useInviteCopy } from './InviteStaffForm';

interface InviteStaffModalProps {
  isOpen: boolean;
  onClose: () => void;
  onInviteLinks: (links: InviteLink[]) => void;
}

const InviteStaffModal = ({ isOpen, onClose, onInviteLinks }: InviteStaffModalProps) => {
  const { t } = useTranslation();
  const copy = useInviteCopy();
  const inviting = useIsMutating({ mutationKey: INVITE_STAFF_MUTATION_KEY }) > 0;

  const close = () => {
    if (inviting) return;
    onClose();
  };

  const invited = (links: InviteLink[]) => {
    onClose();
    if (links.length > 0) onInviteLinks(links);
  };

  return (
    <Dialog open={isOpen} onOpenChange={(open) => { if (!open) close(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('settings.staff.inviteNewStaffMember')}</DialogTitle>
          <DialogDescription>{copy.description}</DialogDescription>
        </DialogHeader>
        <InviteStaffForm onInvited={invited} onCancel={close} />
      </DialogContent>
    </Dialog>
  );
};

export default InviteStaffModal;
