import React, { useId, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/hooks/use-toast';
import { foundPlayers, usePlayerSearch, useAssignMinecraftPlayer, useStaff } from '@/hooks/use-data';
import { PlayerSearchFailure } from '@/components/player/PlayerSearchFailure';
import type { StaffMember } from '@/hooks/data/staff';
import { describeError } from '@/lib/http-error';
import { cn } from '@/lib/utils';

type AssignableStaffMember = Pick<StaffMember, 'email' | 'role' | 'assignedMinecraftUuid' | 'assignedMinecraftUsername'>;

interface AssignMinecraftPlayerModalProps {
  isOpen: boolean;
  onClose: () => void;
  staffMember: AssignableStaffMember | null;
}

const AssignMinecraftPlayerModal: React.FC<AssignMinecraftPlayerModalProps> = ({
  isOpen,
  onClose,
  staffMember
}) => {
  const { t } = useTranslation();
  const searchId = useId();
  const [selectedPlayerUuid, setSelectedPlayerUuid] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const { toast } = useToast();

  const { data: staff } = useStaff();
  const playerSearch = usePlayerSearch(searchQuery);
  const assignPlayerMutation = useAssignMinecraftPlayer();

  const assignedUuids = useMemo(() => new Set(
    (staff ?? []).flatMap((member) => member.assignedMinecraftUuid ? [member.assignedMinecraftUuid] : []),
  ), [staff]);

  const searchResults = foundPlayers(playerSearch);
  const availablePlayers = useMemo(
    () => searchResults.filter((player) => !assignedUuids.has(player.uuid)),
    [searchResults, assignedUuids],
  );
  const selectedPlayer = availablePlayers.find((player) => player.uuid === selectedPlayerUuid);

  const handleClose = () => {
    setSelectedPlayerUuid('');
    setSearchQuery('');
    onClose();
  };

  const handleAssign = () => {
    if (!staffMember || !selectedPlayer) return;

    assignPlayerMutation.mutate({
      email: staffMember.email,
      minecraftUuid: selectedPlayer.uuid,
      minecraftUsername: selectedPlayer.username
    }, {
      onSuccess: handleClose,
      onError: (error) => {
        toast({
          title: t('settings.staff.assignmentFailed'),
          description: describeError(error, t('settings.staff.failedToAssignPlayer')),
          variant: 'destructive'
        });
      },
    });
  };

  const handleClearAssignment = () => {
    if (!staffMember) return;

    assignPlayerMutation.mutate({ email: staffMember.email }, {
      onSuccess: handleClose,
      onError: (error) => {
        toast({
          title: t('settings.staff.clearFailed'),
          description: describeError(error, t('settings.staff.failedToClearAssignment')),
          variant: 'destructive'
        });
      },
    });
  };

  const renderResults = () => {
    if (playerSearch.state === 'inactive') return null;

    if (playerSearch.state === 'pending') {
      return (
        <div className="flex justify-center py-8">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-hidden="true" />
        </div>
      );
    }

    if (playerSearch.state === 'failed') return <PlayerSearchFailure onRetry={playerSearch.retry} />;

    if (availablePlayers.length === 0) {
      return (
        <p className="py-8 text-center text-sm text-muted-foreground">
          {searchResults.length > 0
            ? t('settings.staff.allPlayersAssigned')
            : t('settings.staff.noPlayersFound', { query: searchQuery })}
        </p>
      );
    }

    return (
      <div className="max-h-[300px] overflow-y-auto rounded-md border p-2">
        {availablePlayers.map((player) => (
          <Button
            key={player.uuid}
            type="button"
            variant={selectedPlayerUuid === player.uuid ? 'secondary' : 'ghost'}
            className={cn('mb-1 h-auto w-full justify-start px-3 py-3 text-left', selectedPlayerUuid === player.uuid && 'ring-2 ring-primary')}
            aria-pressed={selectedPlayerUuid === player.uuid}
            onClick={() => setSelectedPlayerUuid(player.uuid)}
          >
            <span className="flex min-w-0 flex-col items-start">
              <span className="w-full truncate text-sm font-medium">{player.username || t('settings.staff.unknownPlayer')}</span>
              <span className="w-full truncate text-xs text-muted-foreground">{player.uuid}</span>
            </span>
          </Button>
        ))}
      </div>
    );
  };

  if (!staffMember) return null;

  return (
    <Dialog open={isOpen} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-2xl max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>{t('settings.staff.assignMinecraftPlayer')}</DialogTitle>
          <DialogDescription>
            {t('settings.staff.assignMinecraftPlayerDesc', { email: staffMember.email, role: staffMember.role })}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 space-y-4 overflow-y-auto">
          {staffMember.assignedMinecraftUuid && (
            <div className="flex items-center justify-between gap-2 rounded-lg bg-muted p-3">
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium">{t('settings.staff.currentlyAssigned')}</span>
                <Badge variant="secondary">{staffMember.assignedMinecraftUsername}</Badge>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleClearAssignment}
                disabled={assignPlayerMutation.isPending}
              >
                {t('settings.staff.clearAssignment')}
              </Button>
            </div>
          )}

          <div className="space-y-3">
            <Label htmlFor={searchId}>{t('settings.staff.searchAndSelectPlayer')}</Label>
            <Input
              id={searchId}
              placeholder={t('settings.staff.searchByUsernameOrUuid')}
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              autoFocus
            />
            {renderResults()}
          </div>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={handleClose} disabled={assignPlayerMutation.isPending}>
            {t('common.cancel')}
          </Button>
          <Button
            type="button"
            onClick={handleAssign}
            disabled={!selectedPlayer || assignPlayerMutation.isPending}
          >
            {assignPlayerMutation.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {t('settings.staff.assignPlayer')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AssignMinecraftPlayerModal;
