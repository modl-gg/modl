import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Loader2 } from 'lucide-react';
import { signInEmail } from '@/lib/form-schemas';
import { Button } from '@/components/ui/button';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { useRoles } from '@/hooks/use-data';
import { useInviteStaff, type InviteLink } from '@/hooks/data/staff';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { SUPER_ADMIN_ROLE } from '@/hooks/use-permissions';
import { useContinueGateActive } from '@/hooks/use-before-continue';

export function useInviteCopy() {
  const { t } = useTranslation();
  const emailDelivery = useEmailDelivery();
  return emailDelivery
    ? {
        description: t('settings.staff.inviteStaffDesc'),
        submit: t('settings.staff.sendInvitation'),
        pending: t('settings.staff.sending'),
        failed: t('settings.staff.sendInvitationFailed'),
      }
    : {
        description: t('settings.staff.inviteStaffLinkDesc'),
        submit: t('settings.staff.createInvitation'),
        pending: t('settings.staff.creating'),
        failed: t('settings.staff.createInvitationFailed'),
      };
}

interface InviteStaffFormProps {
  onInvited: (links: InviteLink[]) => void;
  onCancel?: () => void;
}

export function InviteStaffForm({ onInvited, onCancel }: InviteStaffFormProps) {
  const { t } = useTranslation();
  const { toast } = useToast();
  const inviteStaff = useInviteStaff();
  const copy = useInviteCopy();
  const insideSetup = useContinueGateActive();
  const { data: rolesData } = useRoles();
  const availableRoles = (rolesData?.roles ?? []).filter((role) => role.name !== SUPER_ADMIN_ROLE);

  const inviteSchema = useMemo(() => z.object({
    email: signInEmail(t('settings.staff.invalidEmail')),
    role: z.string().min(1, { error: t('settings.staff.roleRequired') }),
  }), [t]);

  const form = useForm<z.infer<typeof inviteSchema>>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { email: '', role: '' },
  });

  const cancel = () => {
    form.reset();
    onCancel?.();
  };

  const onSubmit = async (values: z.infer<typeof inviteSchema>) => {
    try {
      const links = await inviteStaff.mutateAsync(values);
      form.reset();
      if (links.length === 0) {
        toast({ title: t('toast.success'), description: t('settings.staff.invitationSent') });
      }
      onInvited(links);
    } catch (error) {
      toast({ title: t('toast.error'), description: describeError(error, copy.failed), variant: 'destructive' });
    }
  };

  return (
    <Form {...form}>
      <form onSubmit={(event) => { void form.handleSubmit(onSubmit)(event); }} className="space-y-4">
        <FormField
          control={form.control}
          name="email"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t('settings.staff.email')}</FormLabel>
              <FormControl>
                <Input type="email" autoComplete="off" placeholder={t('auth.emailPlaceholder')} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="role"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t('settings.staff.role')}</FormLabel>
              <Select onValueChange={field.onChange} value={field.value}>
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder={t('settings.staff.selectRole')} />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {availableRoles.map((role) => (
                    <SelectItem key={role.id} value={role.name}>
                      {role.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        <div className={insideSetup ? 'flex gap-2' : 'flex flex-col-reverse gap-2 sm:flex-row sm:justify-end'}>
          {onCancel && (
            <Button type="button" variant="ghost" onClick={cancel} disabled={inviteStaff.isPending}>
              {t('common.cancel')}
            </Button>
          )}
          <Button type="submit" variant={insideSetup ? 'outline' : 'default'} disabled={inviteStaff.isPending}>
            {inviteStaff.isPending ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                {copy.pending}
              </>
            ) : (
              copy.submit
            )}
          </Button>
        </div>
      </form>
    </Form>
  );
}
