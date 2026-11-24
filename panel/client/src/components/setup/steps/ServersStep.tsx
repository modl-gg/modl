import AddServerDialog from '@/components/settings/AddServerDialog';
import ServersList from '@/components/settings/ServersList';

export function ServersStep() {
  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <AddServerDialog />
      </div>
      <ServersList />
    </div>
  );
}
