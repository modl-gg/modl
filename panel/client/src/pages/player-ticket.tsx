import { useState, useEffect, useMemo, type ReactElement } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'wouter';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import {
  useAddTicketReply,
  useRequestTicketVerification,
  useSubmitTicketForm,
  useTicket,
  useVerifyTicketCode,
  type PublicTicket,
  type PublicTicketReplyAttachment,
} from '@/hooks/data/tickets';
import { useSettings } from '@/hooks/data/settings';
import TicketAttachments, { type TicketAttachment } from '@/components/TicketAttachments';
import { TicketStatusBadge } from '@/components/ui/ticket-status-badge';
import { TicketFormBody, TicketSubjectInput } from '@/components/tickets/TicketFormBody';
import { getAvatarUrl } from '@/lib/api';
import { safeExternalHref } from '@/lib/utils';
import { toast } from '@/hooks/use-toast';
import MarkdownRenderer from '@/components/ui/markdown-renderer';
import MarkdownHelp from '@/components/ui/markdown-help';
import { formatDate } from '@/utils/date-utils';
import { getCreatorIdentifier } from '@/utils/creator-verification';
import { Notice } from '@/components/ui/notice';
import { useMediaUpload } from '@/hooks/use-media-upload';
import { resolveTicketEmailPolicy } from '@/utils/ticket-email-policy';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { usePublicRecordAccessKey } from '@/hooks/use-public-record-access';
import { PrivateRecordNotice } from '@/components/public/PrivateRecordNotice';
import { ClaimTicketNotice } from '@/components/tickets/ClaimTicketNotice';
import { readPublicRecordAccessKey, readPublicRecordToken } from '@/lib/public-record-access';
import { normalizeTicketStatus, type NormalizedTicketStatus } from '@/lib/ticket-enums';
import { describeError } from '@/lib/http-error';
import { isThreadFullError } from '@/lib/public-reply';
import { PublicReplyInput } from '@/components/public/PublicReplyInput';
import type { PublicMessageSender } from '@/lib/public-style-hooks';
import { optionalField } from '@/lib/defined-fields';
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

const EMAIL_FIELD_ORDER = 1;
const UNKNOWN_SENDER_NAME = 'Unknown';

type TicketSenderType = 'user' | 'staff' | 'system';

const SENDER_HOOKS: Readonly<Record<TicketSenderType, PublicMessageSender>> = {
  user: 'player',
  staff: 'staff',
  system: 'system',
};

interface MessageAttachment {
  key: string;
  url: string;
  fileName: string;
}

interface TicketMessage {
  id: string;
  sender: string;
  senderType: TicketSenderType;
  content: string;
  timestamp: string;
  staff: boolean;
  attachments: MessageAttachment[];
  closedAs: string;
  avatar: string;
  creatorIdentifier: string;
}

interface TicketDetails {
  id: string;
  subject: string;
  status: NormalizedTicketStatus;
  creatorName: string;
  date: string;
  category: string;
  type: string;
  messages: TicketMessage[];
  locked: boolean;
}

const NON_CLOSING_ACTIONS: ReadonlySet<string> = new Set(['Comment', 'Reopen']);

function senderTypeOf(type: string): TicketSenderType {
  if (type === 'staff') return 'staff';
  if (type === 'system') return 'system';
  return 'user';
}

function toTicketDetails(ticket: PublicTicket): TicketDetails {
  return {
    id: ticket.id || ticket._id,
    subject: ticket.subject,
    status: normalizeTicketStatus(ticket.status),
    creatorName: ticket.creatorName || ticket.reportedBy,
    date: ticket.created ?? ticket.date ?? new Date().toISOString(),
    category: ticket.category,
    type: (ticket.type || 'bug').toLowerCase(),
    locked: ticket.locked,
    messages: ticket.replies.map((reply, index) => {
      const id = reply.id || `reply-${index}`;
      return {
        id,
        sender: reply.name,
        senderType: senderTypeOf(reply.type),
        content: reply.content,
        timestamp: reply.created ?? new Date().toISOString(),
        staff: reply.staff,
        attachments: toMessageAttachments(reply.attachments, id),
        closedAs: NON_CLOSING_ACTIONS.has(reply.action) ? '' : reply.action,
        creatorIdentifier: reply.creatorIdentifier,
        avatar: reply.avatar,
      };
    }),
  };
}

function toMessageAttachments(attachments: readonly PublicTicketReplyAttachment[], messageId: string): MessageAttachment[] {
  return attachments.map((attachment, index) => ({
    key: `${messageId}:${index}`,
    url: attachment.url,
    fileName: attachment.fileName || attachment.url.split('/').pop() || `attachment-${index + 1}`,
  }));
}

const AvatarFallback = ({ initials, className, textClassName }: { initials: string; className: string; textClassName: string }) => (
  <div className={`h-8 w-8 rounded-md flex items-center justify-center shrink-0 ${className}`}>
    <span className={`text-xs font-bold ${textClassName}`}>{initials}</span>
  </div>
);

const AvatarImage = ({ src, alt, initials, fallback }: { src: string; alt: string; initials: string; fallback: ReactElement }) => {
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);

  if (failed) return fallback;

  return (
    <div className="relative h-8 w-8 bg-muted rounded-md flex items-center justify-center overflow-hidden shrink-0">
      <img
        src={src}
        alt={alt}
        className={`w-full h-full object-cover transition-opacity duration-200 ${loading ? 'opacity-0' : 'opacity-100'}`}
        onError={() => {
          setFailed(true);
          setLoading(false);
        }}
        onLoad={() => setLoading(false)}
      />
      {loading && (
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-xs font-bold text-primary">{initials}</span>
        </div>
      )}
    </div>
  );
};

const MessageAvatar = ({ message, creatorUuid }: { message: TicketMessage, creatorUuid: string }) => {
  const { t } = useTranslation();
  const alt = t('appeals.avatarAlt', { name: message.sender });
  const senderInitials = message.sender.substring(0, 2);

  if (message.senderType === 'user') {
    const initials = senderInitials || 'U';
    const fallback = <AvatarFallback initials={initials} className="bg-primary/15" textClassName="text-primary" />;
    return creatorUuid !== ''
      ? <AvatarImage key={creatorUuid} src={getAvatarUrl(creatorUuid, 32, true)} alt={alt} initials={initials} fallback={fallback} />
      : fallback;
  }

  if (message.senderType === 'staff' || message.staff) {
    const initials = senderInitials || 'S';
    const fallback = <AvatarFallback initials={initials} className="bg-success/15" textClassName="text-success" />;
    return message.avatar !== ''
      ? <AvatarImage key={message.avatar} src={message.avatar} alt={alt} initials={initials} fallback={fallback} />
      : fallback;
  }

  return <AvatarFallback initials="SY" className="bg-muted" textClassName="text-muted-foreground" />;
};

const PlayerTicket = () => {
  const { id } = useParams();
  const ticketId = id ?? '';
  const { t } = useTranslation();
  const [newReply, setNewReply] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formSubject, setFormSubject] = useState('');
  const { formData, pendingFiles, setField, selectFile } = useTicketFormDraft();
  const { reportValidationProblem, reportHiddenFiles } = useTicketFormNotices();
  const [replyAttachments, setReplyAttachments] = useState<TicketAttachment[]>([]);
  const [pendingMessages, setPendingMessages] = useState<TicketMessage[]>([]);
  const { uploadMedia } = useMediaUpload();
  const emailDelivery = useEmailDelivery();

  const accessKeyAdopted = usePublicRecordAccessKey(id);
  const ticketQuery = useTicket(ticketId, accessKeyAdopted);
  const { data: ticketData, isLoading: isLoadingTicket, isError } = ticketQuery;
  const isLoading = isLoadingTicket || !accessKeyAdopted;

  const { data: settingsData, isLoading: isLoadingSettings } = useSettings();
  const addReplyMutation = useAddTicketReply();
  const submitFormMutation = useSubmitTicketForm();
  const requestVerificationMutation = useRequestTicketVerification();
  const verifyCodeMutation = useVerifyTicketCode();

  const [verificationCode, setVerificationCode] = useState('');
  const [verificationEmailHint, setVerificationEmailHint] = useState('');
  const [codeSent, setCodeSent] = useState(false);

  const loadedTicket = ticketData && !('requiresVerification' in ticketData) ? ticketData : null;
  const loadedTicketDetails = useMemo(() => (loadedTicket ? toTicketDetails(loadedTicket) : null), [loadedTicket]);

  const ticketForms = settingsData?.settings.ticketForms;
  const ticketType = loadedTicketDetails?.type;
  const formConfig = useMemo(
    () => (ticketType === undefined ? null : ticketFormFor(ticketForms, ticketType)),
    [ticketForms, ticketType],
  );

  useEffect(() => {
    if (loadedTicketDetails?.id) {
      getCreatorIdentifier(loadedTicketDetails.id);
    }
  }, [loadedTicketDetails?.id]);

  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen p-4">
        <Loader2 className="w-8 h-8 animate-spin text-primary mb-4" />
        <p className="text-muted-foreground">{t('playerTicket.loading')}</p>
      </div>
    );
  }

  if (isError || !ticketData) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen p-4">
        <div className="bg-destructive/10 text-destructive-text rounded-lg p-6 max-w-md">
          <h2 className="text-xl font-semibold mb-2">{t('playerTicket.notFound')}</h2>
          <p className="mb-4">{t('playerTicket.notFoundDesc')}</p>
        </div>
      </div>
    );
  }

  if ('requiresVerification' in ticketData) {
    const handleRequestCode = () => {
      requestVerificationMutation.mutate(ticketId, {
        onSuccess: (result) => {
          setVerificationEmailHint(result.emailHint || ticketData.emailHint);
          setCodeSent(true);
          toast({
            title: t('playerTicket.codeSent'),
            description: t('playerTicket.codeSentDesc'),
          });
        },
        onError: (error) => {
          toast({
            title: t('playerTicket.codeFailed'),
            description: describeError(error, t('playerTicket.tryAgain')),
            variant: "destructive"
          });
        },
      });
    };

    const handleVerifyCode = () => {
      const code = verificationCode.trim();
      if (!code) return;
      verifyCodeMutation.mutate({ ticketId, code }, {
        onSuccess: (result) => {
          if (result.token) {
            toast({
              title: t('playerTicket.verified'),
            });
          }
        },
        onError: (error) => {
          toast({
            title: t('playerTicket.verificationFailed'),
            description: describeError(error, t('playerTicket.invalidCode')),
            variant: "destructive"
          });
        },
      });
    };

    return (
      <div className="min-h-screen flex items-center justify-center p-4">
        <Card data-modl="ticket-verification" className="w-full max-w-md shadow-card">
          <CardHeader>
            <CardTitle>{t('playerTicket.verificationRequired')}</CardTitle>
            {ticketData.emailHint && (
              <CardDescription>
                {t('playerTicket.verificationRequiredDesc')} {t('playerTicket.codeSentTo', { email: ticketData.emailHint })}
              </CardDescription>
            )}
          </CardHeader>
          <CardContent className="space-y-4">
            {!ticketData.emailHint ? (
              <PrivateRecordNotice />
            ) : !codeSent ? (
              <Button onClick={handleRequestCode} disabled={requestVerificationMutation.isPending} className="w-full">
                {requestVerificationMutation.isPending ? (
                  <><Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" /> {t('playerTicket.sending')}</>
                ) : (
                  t('playerTicket.sendVerificationCode')
                )}
              </Button>
            ) : (
              <>
                <div className="space-y-2">
                  <Label htmlFor="verificationCode">{t('playerTicket.enterCode', { email: verificationEmailHint || ticketData.emailHint })}</Label>
                  <Input
                    id="verificationCode"
                    type="text"
                    placeholder="000000"
                    value={verificationCode}
                    onChange={(e) => setVerificationCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                    maxLength={6}
                    className="text-center text-lg tracking-widest"
                  />
                </div>
                <Button onClick={handleVerifyCode} disabled={verifyCodeMutation.isPending || verificationCode.length !== 6} className="w-full">
                  {verifyCodeMutation.isPending ? (
                    <><Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" /> {t('playerTicket.verifying')}</>
                  ) : (
                    t('playerTicket.verifyCode')
                  )}
                </Button>
                <Button variant="ghost" size="sm" onClick={handleRequestCode} disabled={requestVerificationMutation.isPending} className="w-full">
                  {t('playerTicket.resendCode')}
                </Button>
              </>
            )}
          </CardContent>
        </Card>
      </div>
    );
  }

  const ticketDetails = loadedTicketDetails ?? toTicketDetails(ticketData);
  const messages = [...ticketDetails.messages, ...pendingMessages];
  const isApplication = isApplicationTicketType(ticketDetails.type);

  const sendReply = async (content: string, attachments: TicketAttachment[]) => {
    const senderName = ticketDetails.creatorName || UNKNOWN_SENDER_NAME;
    const creatorIdentifier = getCreatorIdentifier(ticketDetails.id);
    const timestamp = new Date().toISOString();
    const pendingMessage: TicketMessage = {
      id: `pending-${Date.now()}`,
      sender: senderName,
      senderType: 'user',
      content,
      timestamp,
      staff: false,
      attachments: attachments.map((attachment) => ({ key: attachment.id, url: attachment.url, fileName: attachment.fileName })),
      closedAs: '',
      avatar: '',
      creatorIdentifier,
    };
    const removePendingMessage = () => {
      setPendingMessages((prev) => prev.filter((message) => message.id !== pendingMessage.id));
    };

    setPendingMessages((prev) => [...prev, pendingMessage]);
    setNewReply('');
    setReplyAttachments([]);

    try {
      await addReplyMutation.mutateAsync({
        id: ticketDetails.id,
        reply: {
          content,
          attachments: attachments.map((attachment) => attachment.url),
          creatorIdentifier,
        },
      });
      await ticketQuery.refetch({ cancelRefetch: false });
      removePendingMessage();
    } catch (error: unknown) {
      toast({
        title: t('playerTicket.replyFailed'),
        ...(isThreadFullError(error) ? { description: t('publicReply.threadFull') } : {}),
        variant: "destructive"
      });
      removePendingMessage();
      setNewReply(content);
      setReplyAttachments(attachments);
    }
  };

  const handleSendReply = () => {
    const content = newReply.trim();
    if (!content) return;
    void sendReply(content, replyAttachments);
  };

  const completeTicket = async (
    form: TicketFormSettings,
    subject: string,
    visibleSections: ReadonlySet<string>,
    email: string | null,
    emailAuthSelected: boolean,
  ) => {
    const prepared = await prepareTicketSubmission(form, formData, visibleSections, pendingFiles, async (file, field) =>
      uploadMedia(file, 'ticket', { ticketId: ticketDetails.id, ticketType: ticketDetails.type, fieldId: field.id }));
    reportHiddenFiles(prepared.hiddenExcludedFiles);

    await submitFormMutation.mutateAsync({
      id: ticketDetails.id,
      formData: {
        subject,
        ...optionalField('creatorEmail', email ?? undefined),
        emailAuthEnabled: emailAuthSelected,
        formData: prepared.formData,
        fieldLabels: prepared.fieldLabels,
        attachments: prepared.attachments,
        creatorIdentifier: getCreatorIdentifier(ticketDetails.id)
      }
    });

    window.location.reload();
  };

  const submitForm = async (config: TicketFormSettings) => {
    const finalSubject = isApplication
      ? t('submitTicket.staffApplicationSubject', { name: ticketDetails.creatorName || t('submitTicket.defaultApplicantName') })
      : formSubject.trim();

    if (!isApplication && !finalSubject) {
      toast({
        title: t('submitTicket.subjectRequired'),
        description: t('submitTicket.subjectRequiredDesc'),
        variant: "destructive"
      });
      return;
    }

    const emailPolicy = resolveTicketEmailPolicy(config, emailDelivery);
    const emailField = ticketEmailField(t('submitTicket.emailAddress'), t('submitTicket.emailAddressDesc'), emailPolicy.emailRequired, EMAIL_FIELD_ORDER);
    const validation = validateTicketForm(config, formData, emailPolicy, emailField);

    if (validation.kind !== 'valid') {
      reportValidationProblem(validation);
      return;
    }

    setIsSubmitting(true);
    try {
      await completeTicket(config, finalSubject, validation.visibleSections, validation.email, validation.emailAuthSelected);
    } catch (error) {
      toast({
        title: t('playerTicket.submissionFailed'),
        description: describeError(error, t('playerTicket.submissionFailedDesc')),
        variant: "destructive"
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

    if (formConfig === null) {
      return (
        <Notice variant="error">
          {t('submitTicket.noFormConfig', { type: ticketDetails.type })}
        </Notice>
      );
    }

    const renderEmailPolicy = resolveTicketEmailPolicy(formConfig, emailDelivery);
    const emailDescription = emailDelivery ? t('submitTicket.emailAddressDesc') : t('submitTicket.contactEmailDesc');
    const fields = fieldsWithEmail(
      formConfig,
      renderEmailPolicy,
      ticketEmailField(t('submitTicket.emailAddress'), emailDescription, renderEmailPolicy.emailRequired, EMAIL_FIELD_ORDER),
    );

    if (fields.length === 0) {
      return (
        <Notice variant="warning">
          {t('submitTicket.emptyFormConfigDesc', { type: ticketDetails.type })}
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
        uploadMetadata={{ ticketId: ticketDetails.id, ticketType: ticketDetails.type }}
        isApplication={isApplication}
        emailAuthOptional={renderEmailPolicy.emailAuthOptional}
        isSubmitting={isSubmitting}
        onSubmit={() => { void submitForm(formConfig); }}
        leadingFields={isApplication ? null : <TicketSubjectInput value={formSubject} onChange={setFormSubject} />}
      />
    );
  };

  const formTitleKey = (): string => {
    if (isApplication) return 'playerTicket.staffApplication';
    switch (ticketDetails.type) {
      case 'bug':
        return 'playerTicket.completeBugReport';
      case 'player':
        return 'playerTicket.completePlayerReport';
      case 'chat':
        return 'playerTicket.completeChatReport';
      case 'support':
        return 'playerTicket.completeSupportRequest';
      default:
        return 'playerTicket.completeTicket';
    }
  };


  const originalCreatorId = messages.find((message) => message.senderType === 'user' && message.creatorIdentifier !== '')?.creatorIdentifier ?? '';
  const isUnverifiedReply = (message: TicketMessage, index: number) =>
    index > 0
    && message.senderType === 'user'
    && originalCreatorId !== ''
    && message.creatorIdentifier !== ''
    && message.creatorIdentifier !== originalCreatorId;

  return (
    <TooltipProvider>
      <div className="min-h-screen p-6">
        <div className="max-w-5xl mx-auto">
        {!ticketData.emailAuthEnabled && (
          <Notice variant="warning" className="mb-6">
            {t('submitTicket.securityNoticeDesc')}
          </Notice>
        )}

        {ticketDetails.status === 'unfinished' ? (
          <Card data-modl="form-card" className="mb-6 shadow-card">
            <CardHeader>
              <CardTitle data-modl="form-title">{t(formTitleKey(), { type: ticketDetails.type })}</CardTitle>
            </CardHeader>
            <CardContent>
              {renderTicketForm()}
            </CardContent>
          </Card>
        ) : (
          <>
            <div data-modl="ticket" className="bg-card rounded-lg shadow-xs border border-border overflow-hidden mb-6">
              <div data-modl="ticket-header" className="space-y-3 border-b p-4">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <h1 data-modl="page-title" className="text-xl font-semibold">{ticketDetails.subject || t('playerTicket.noSubject')}</h1>
                  <div className="flex gap-2">
                    <Badge variant="outline">
                      {ticketDetails.category || t('playerTicket.defaultCategory')}
                    </Badge>
                    <TicketStatusBadge status={ticketDetails.status} />
                  </div>
                </div>
                <p className="text-sm text-muted-foreground">
                  {t('playerTicket.reportedBy', { name: ticketDetails.creatorName || t('common.unknown') })} · {formatDate(ticketDetails.date)}
                </p>

                {!ticketData.creatorLinked && (
                  <ClaimTicketNotice
                    title={t('playerTicket.linkMinecraftAccount')}
                    commandIntro={t('ticketClaim.runCommand')}
                    ticketId={ticketDetails.id}
                    accessKey={ticketData.emailAuthEnabled
                      ? readPublicRecordToken(ticketDetails.id)
                      : readPublicRecordAccessKey(ticketDetails.id)}
                  />
                )}
              </div>
              <div data-modl="message-list" className="divide-y">
                {messages.map((message, index) => (
                  <div data-modl="message" data-modl-sender={SENDER_HOOKS[message.senderType]} key={message.id} className="p-4">
                    <div className="flex items-start gap-3">
                      <MessageAvatar message={message} creatorUuid={ticketData.creatorUuid} />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-medium text-sm">{message.sender || t('common.unknown')}</span>
                          <span className="text-xs text-muted-foreground">
                            {formatDate(message.timestamp)}
                          </span>
                          {message.senderType === 'staff' && (
                            <Badge variant="secondary">
                              {t('playerTicket.staffBadge')}
                            </Badge>
                          )}
                          {message.senderType === 'system' && (
                            <Badge variant="outline">
                              {t('playerTicket.systemBadge')}
                            </Badge>
                          )}
                          {message.closedAs && (
                            <Badge variant="outline">
                              {message.closedAs}
                            </Badge>
                          )}
                          {isUnverifiedReply(message, index) && (
                            <Tooltip delayDuration={300}>
                              <TooltipTrigger asChild>
                                <Badge
                                  variant="destructive"
                                  className="cursor-help"
                                  tabIndex={0}
                                >
                                  {t('playerTicket.unverifiedBadge')}
                                </Badge>
                              </TooltipTrigger>
                              <TooltipContent className="max-w-xs p-3 z-50" side="top">
                                <p className="text-sm">{t('playerTicket.unverifiedExplanation')}</p>
                              </TooltipContent>
                            </Tooltip>
                          )}
                        </div>
                        <div className="text-sm">
                          <MarkdownRenderer content={message.content} disableClickablePlayers={true} />
                        </div>
                        {message.attachments.length > 0 && (
                          <div className="mt-2 space-y-1">
                            {message.attachments.map((attachment) => {
                              const { fileName } = attachment;
                              return (
                                <div key={attachment.key} className="text-sm">
                                  <a
                                    href={safeExternalHref(attachment.url)}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="text-primary hover:text-primary/80 underline underline-offset-2"
                                    title={t('playerTicket.downloadFile', { name: fileName })}
                                  >
                                    {fileName}
                                  </a>
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {ticketDetails.status === 'open' && !ticketDetails.locked && (
              <Card data-modl="reply" className="shadow-card">
                <CardContent className="pt-6">
                  <div className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="reply">{t('playerTicket.replyLabel')}</Label>
                      <PublicReplyInput
                        id="reply"
                        value={newReply}
                        onChange={setNewReply}
                      />
                    </div>

                    <div className="flex items-center justify-between gap-4">
                      <div className="flex flex-1 flex-wrap items-center gap-2">
                        <TicketAttachments
                          ticketId={ticketDetails.id}
                          ticketType={ticketDetails.type}
                          attachments={replyAttachments}
                          onAdd={(attachment) => { setReplyAttachments((current) => [...current, attachment]); }}
                          onRemove={(attachment) => { setReplyAttachments((current) => current.filter((candidate) => candidate.id !== attachment.id)); }}
                        />
                        <MarkdownHelp />
                      </div>

                      <Button
                        data-modl="submit-button"
                        onClick={handleSendReply}
                        disabled={!newReply.trim() || addReplyMutation.isPending}
                        className="flex items-center"
                      >
                        {addReplyMutation.isPending ? (
                          <>
                            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                            {t('playerTicket.sending')}
                          </>
                        ) : (
                          t('playerTicket.sendReply')
                        )}
                      </Button>
                    </div>
                  </div>
                </CardContent>
              </Card>
            )}
          </>
        )}
        </div>
      </div>
    </TooltipProvider>
  );
};

export default PlayerTicket;
