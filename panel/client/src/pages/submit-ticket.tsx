import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useParams } from 'wouter';
import { Loader2, ArrowLeft } from 'lucide-react';
import { toInAppPath } from '@/lib/utils';
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useToast } from '@/hooks/use-toast';
import { useSettings } from "@/hooks/data/settings";
import { useCreatePublicTicket } from '@/hooks/data/tickets';
import { getCreatorIdentifier } from '@/utils/creator-verification';
import { useMediaUpload } from '@/hooks/use-media-upload';
import { resolveTicketEmailPolicy } from '@/utils/ticket-email-policy';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { Notice } from '@/components/ui/notice';
import { TicketFormBody, TicketSubjectInput } from '@/components/tickets/TicketFormBody';
import { describeError } from '@/lib/http-error';
import {
  fieldsWithEmail,
  isApplicationTicketType,
  prepareTicketSubmission,
  ticketEmailField,
  ticketFormFor,
  validateTicketForm,
} from '@/lib/ticket-form';
import { useTicketFormDraft, useTicketFormNotices } from '@/hooks/use-ticket-form-draft';
import type { TicketFormSettings } from '@/types/forms';

const TYPE_ALIASES: ReadonlyMap<string, string> = new Map([
  ['support', 'support'],
  ['bug', 'bug'],
  ['staff', 'staff'],
  ['application', 'staff'],
  ['apply', 'staff'],
]);

const SELECTABLE_TYPES = ['support', 'bug', 'staff'] as const;
type SelectableTicketType = typeof SELECTABLE_TYPES[number];

const CREATOR_NAME_MAX_LENGTH = 16;
const EMAIL_FIELD_ORDER = 2;

function selectableType(value: string | undefined): SelectableTicketType | undefined {
  return SELECTABLE_TYPES.find((type) => type === value);
}

const SubmitTicketPage = () => {
  const [, setLocation] = useLocation();
  const { type: urlType } = useParams<{ type?: string }>();
  const { toast } = useToast();
  const { t } = useTranslation();

  const ticketTypes = [
    { id: 'support', label: t('submitTicket.supportRequest') },
    { id: 'bug', label: t('submitTicket.bugReport') },
    { id: 'staff', label: t('submitTicket.staffApplication') },
  ] as const;

  const [selectedType, setSelectedType] = useState<SelectableTicketType | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formSubject, setFormSubject] = useState('');
  const { formData, pendingFiles, setField, selectFile } = useTicketFormDraft();
  const { reportValidationProblem, reportHiddenFiles } = useTicketFormNotices();
  const [displayName, setDisplayName] = useState('');
  const { uploadMedia } = useMediaUpload();
  const emailDelivery = useEmailDelivery();
  const createTicketMutation = useCreatePublicTicket();

  const { data: settingsData, isLoading: isLoadingSettings } = useSettings();

  const effectiveType = selectedType ?? selectableType(urlType === undefined ? undefined : TYPE_ALIASES.get(urlType.toLowerCase()));
  const apiTicketType = effectiveType === 'staff' ? 'application' : effectiveType;
  const isApplication = effectiveType !== undefined && isApplicationTicketType(effectiveType);
  const ticketForms = settingsData?.settings.ticketForms;
  const formConfig = useMemo(
    () => (effectiveType === undefined ? null : ticketFormFor(ticketForms, effectiveType)),
    [ticketForms, effectiveType],
  );

  const createTicket = async (
    form: TicketFormSettings,
    ticketType: string,
    subject: string,
    visibleSections: ReadonlySet<string>,
    email: string | null,
    emailAuthSelected: boolean,
  ) => {
    const prepared = await prepareTicketSubmission(form, formData, visibleSections, pendingFiles, async (file, field) =>
      uploadMedia(file, 'ticket', { ticketId: 'new', ticketType, fieldId: field.id }));
    reportHiddenFiles(prepared.hiddenExcludedFiles);

    const created = await createTicketMutation.mutateAsync({
      type: ticketType,
      subject,
      creatorName: displayName.trim().slice(0, CREATOR_NAME_MAX_LENGTH),
      creatorEmail: email,
      formData: prepared.formData,
      fieldLabels: prepared.fieldLabels,
      attachments: prepared.attachments,
      creatorIdentifier: getCreatorIdentifier(`temp-${Date.now()}`),
      emailAuthEnabled: emailAuthSelected,
    });

    toast({
      title: t('submitTicket.ticketSubmitted'),
      description: t('submitTicket.ticketSubmittedDesc'),
      variant: "default"
    });

    setLocation(created.publicUrl ? toInAppPath(created.publicUrl) : '/');
  };

  const submitForm = async () => {
    if (effectiveType === undefined || apiTicketType === undefined) return;

    const finalSubject = isApplication
      ? t('submitTicket.staffApplicationSubject', { name: displayName.trim() || t('submitTicket.defaultApplicantName') })
      : formSubject.trim();

    if (!isApplication && !finalSubject) {
      toast({
        title: t('submitTicket.subjectRequired'),
        description: t('submitTicket.subjectRequiredDesc'),
        variant: "destructive"
      });
      return;
    }

    if (formConfig === null) {
      toast({
        title: t('submitTicket.formConfigMissing'),
        description: t('submitTicket.formConfigMissingDesc', { type: effectiveType }),
        variant: "destructive"
      });
      return;
    }

    if (!displayName.trim()) {
      toast({
        title: t('submitTicket.nameRequired'),
        description: t('submitTicket.nameRequiredDesc'),
        variant: "destructive"
      });
      return;
    }

    const emailPolicy = resolveTicketEmailPolicy(formConfig, emailDelivery);
    const emailField = ticketEmailField(t('submitTicket.emailAddress'), t('submitTicket.emailAddressDesc'), emailPolicy.emailRequired, 1);
    const validation = validateTicketForm(formConfig, formData, emailPolicy, emailField);

    if (validation.kind !== 'valid') {
      reportValidationProblem(validation);
      return;
    }

    setIsSubmitting(true);
    try {
      await createTicket(formConfig, apiTicketType, finalSubject, validation.visibleSections, validation.email, validation.emailAuthSelected);
    } catch (error) {
      toast({
        title: t('toast.error'),
        description: describeError(error, t('submitTicket.createFailed')),
        variant: 'destructive',
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const renderTicketForm = () => {
    if (isLoadingSettings) {
      return (
        <div className="flex justify-center py-8">
          <Loader2 className="h-6 w-6 animate-spin text-primary" aria-label={t('submitTicket.loadingForm')} />
        </div>
      );
    }

    if (effectiveType === undefined) return null;

    if (formConfig === null) {
      return (
        <Notice variant="error">
          {t('submitTicket.noFormConfig', { type: effectiveType })}
        </Notice>
      );
    }

    const renderEmailPolicy = resolveTicketEmailPolicy(formConfig, emailDelivery);
    const emailDescription = !emailDelivery
      ? t('submitTicket.contactEmailDesc')
      : renderEmailPolicy.emailRequired ? t('submitTicket.emailAddressDesc') : t('submitTicket.emailOptionalDesc');
    const fields = fieldsWithEmail(
      formConfig,
      renderEmailPolicy,
      ticketEmailField(t('submitTicket.emailAddress'), emailDescription, renderEmailPolicy.emailRequired, EMAIL_FIELD_ORDER),
    );

    if (fields.length === 0) {
      return (
        <Notice variant="warning">
          {t('submitTicket.emptyFormConfigDesc', { type: effectiveType })}
        </Notice>
      );
    }

    return (
      <TicketFormBody
        form={{ ...formConfig, fields }}
        formData={formData}
        onFieldChange={setField}
        pendingFiles={pendingFiles}
        onFileSelected={selectFile}
        uploadMetadata={{ ticketId: 'new', ticketType: apiTicketType }}
        isApplication={isApplication}
        emailAuthOptional={renderEmailPolicy.emailAuthOptional}
        isSubmitting={isSubmitting}
        onSubmit={() => { void submitForm(); }}
        leadingFields={
          <>
            <div data-modl="form-field">
              <Label htmlFor="displayName" className="mb-2 block font-medium">
                {t('submitTicket.yourName')}
                <span className="text-destructive ml-1">*</span>
              </Label>
              <Input
                id="displayName"
                type="text"
                autoComplete="name"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                required
              />
            </div>

            {!isApplication && <TicketSubjectInput value={formSubject} onChange={setFormSubject} />}
          </>
        }
      />
    );
  };

  const formTitle = (type: SelectableTicketType) => {
    switch (type) {
      case 'staff':
        return t('submitTicket.staffApplication');
      case 'bug':
        return t('submitTicket.bugReport');
      case 'support':
        return t('submitTicket.supportRequest');
    }
  };

  if (effectiveType !== undefined) {

    return (
      <div className="min-h-screen p-6">
        <div className="max-w-5xl mx-auto">
          <Card data-modl="form-card" className="mb-6 shadow-card">
            <CardHeader>
              {!urlType && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setSelectedType(null)}
                  className="w-fit mb-2 -ml-2"
                >
                  <ArrowLeft className="h-4 w-4 mr-2" />
                  {t('common.back')}
                </Button>
              )}
              <CardTitle data-modl="form-title">{formTitle(effectiveType)}</CardTitle>
            </CardHeader>
            <CardContent>
              {renderTicketForm()}
            </CardContent>
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <Card data-modl="ticket-types" className="w-full max-w-lg shadow-card">
        <CardHeader>
          <CardTitle>{t('submitTicket.submitATicket')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {ticketTypes.map((type) => (
            <Button
              data-modl="ticket-type"
              key={type.id}
              variant="outline"
              onClick={() => setSelectedType(type.id)}
              className="w-full justify-start"
            >
              {type.label}
            </Button>
          ))}
        </CardContent>
      </Card>
    </div>
  );
};

export default SubmitTicketPage;
