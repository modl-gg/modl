import { useState } from 'react';
import type { PublicMessageSender } from '@/lib/public-style-hooks';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'wouter';
import { z } from 'zod';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import type { JsonObject } from '@bufbuild/protobuf';
import { File, Image, Video, FileText } from 'lucide-react';
import { formatDate } from '../utils/date-utils';
import { getAvatarUrl } from '@/lib/api';
import { openExternalUrl, toInAppPath } from '@/lib/utils';
import { PrivateRecordNotice } from '@/components/public/PrivateRecordNotice';
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import MediaUpload from '@/components/MediaUpload';
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { StatusBanner } from "@/components/ui/status-banner";
import { useToast } from '@/hooks/use-toast';
import { Badge } from '@/components/ui/badge';
import { Chip, ChipAction, ChipButton, ChipRemoveButton } from '@/components/ui/chip';
import {
  fetchPublicAppeal,
  fetchPunishmentAppealInfo,
  postPublicAppealReply,
  useCreateAppeal,
  useRequestAppealVerification,
  useVerifyAppealCode,
  type PublicAppealAttachment,
  type PublicAppealReply,
  type PublicPunishmentAppealInfo,
} from '@/hooks/data/appeals';
import { useEmailDelivery } from '@/hooks/use-public-settings';
import { resolveAppealEmailPolicy } from '@/utils/ticket-email-policy';
import { deriveAppealStatus, isTerminalAppealStatus, type NormalizedAppealStatus } from '@/lib/ticket-enums';
import type { AppealFormField, AppealFormSection, AppealFormSettings } from '@/types/forms';
import {
  FALLBACK_REASON_FIELD_ID,
  appealFieldDefaults,
  appealFieldSchemas,
  selectedOptions,
  textValue,
  uploadedFiles,
  type AppealFieldFile,
  type AppealFieldValue,
  type AppealFormValues,
} from '@/lib/appeal-form';
import { trimmedEmail } from '@/lib/form-schemas';
import { byOrder } from '@/lib/form-definition';
import { ApiHttpError, describeError } from '@/lib/http-error';
import { isThreadFullError } from '@/lib/public-reply';
import { PublicReplyInput } from '@/components/public/PublicReplyInput';

const banIdSchema = z.string().min(6, { error: "Ban ID must be at least 6 characters" });

const searchSchema = z.object({
  banId: banIdSchema,
});

type SearchFormValues = z.infer<typeof searchSchema>;

const OCTET_STREAM = 'application/octet-stream';
const APPEAL_STATUS_LABEL_KEYS: Readonly<Record<NormalizedAppealStatus, string>> = {
  open: 'appeals.state.open',
  under_review: 'appeals.state.underReview',
  pending_player_response: 'appeals.state.replyNeeded',
  approved: 'appeals.state.approved',
  rejected: 'appeals.state.rejected',
  closed: 'appeals.state.closed',
};
const SYSTEM_FIELD_IDS: ReadonlySet<string> = new Set(['banId', 'email']);
const REASON_FIELD_IDS: ReadonlySet<string> = new Set(['reason', 'appeal_reason', 'why_appeal', 'explanation']);
const EVIDENCE_FIELD_IDS: ReadonlySet<string> = new Set(['evidence', 'proof', 'screenshots', 'links']);

interface AppealMessageAttachment extends PublicAppealAttachment {
  key: string;
}

interface AppealMessage {
  id: string;
  sender: Exclude<PublicMessageSender, 'system'>;
  senderName: string;
  content: string;
  timestamp: string;
  attachments: AppealMessageAttachment[];
}

interface AppealInfo {
  id: string;
  banId: string;
  submittedOn: string;
  status: NormalizedAppealStatus;
  locked: boolean;
  lastUpdate: string | null;
  messages: AppealMessage[];
}

interface ReplyAttachment {
  id: string;
  url: string;
  key: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  uploadedAt: string;
  uploadedBy: string;
}

function replyAttachmentJson(attachment: ReplyAttachment): JsonObject {
  return {
    id: attachment.id,
    url: attachment.url,
    key: attachment.key,
    fileName: attachment.fileName,
    fileType: attachment.fileType,
    fileSize: attachment.fileSize,
    uploadedAt: attachment.uploadedAt,
    uploadedBy: attachment.uploadedBy,
  };
}

function lastPathSegment(url: string): string {
  return url.split('/').pop() || 'file';
}

function fileIcon(type: string) {
  if (type.startsWith('image/')) return <Image className="h-3 w-3" />;
  if (type.startsWith('video/')) return <Video className="h-3 w-3" />;
  if (type === 'application/pdf') return <FileText className="h-3 w-3" />;
  return <File className="h-3 w-3" />;
}

function truncateFileName(fileName: string, maxLength = 15): string {
  if (fileName.length <= maxLength) return fileName;
  const extensionStart = fileName.lastIndexOf('.');
  if (extensionStart <= 0) return `${fileName.substring(0, Math.max(0, maxLength - 3))}...`;
  const extension = fileName.substring(extensionStart + 1);
  const name = fileName.substring(0, extensionStart);
  return `${name.substring(0, Math.max(0, maxLength - extension.length - 4))}....${extension}`;
}

function formatPunishmentDate(date: Date | null, fallback: string): string {
  return date === null ? fallback : formatDate(date.toISOString());
}

function toAppealMessage(reply: PublicAppealReply, index: number): AppealMessage {
  const id = reply.id || `reply-${index}`;
  return {
    id,
    sender: reply.staff ? 'staff' : 'player',
    senderName: reply.name,
    content: reply.content,
    timestamp: reply.created ?? '',
    attachments: reply.attachments.map((attachment, position) => ({ ...attachment, key: `${id}:${position}` })),
  };
}

function fileFieldAttachment(file: string | AppealFieldFile): JsonObject[] {
  if (typeof file === 'string') {
    return file.trim() ? [{ url: file, fileName: lastPathSegment(file), fileType: OCTET_STREAM }] : [];
  }
  if (!file.url) return [];
  return [{
    url: file.url,
    fileName: file.fileName || lastPathSegment(file.url),
    fileType: file.fileType || OCTET_STREAM,
    fileSize: file.fileSize,
  }];
}

function fileFieldAttachments(value: AppealFieldValue | undefined): JsonObject[] {
  const files: (string | AppealFieldFile)[] = typeof value === 'string' ? [value] : Array.isArray(value) ? value : [];
  return files.flatMap(fileFieldAttachment);
}

function isReasonField(field: AppealFormField): boolean {
  const label = field.label.toLowerCase();
  return REASON_FIELD_IDS.has(field.id.toLowerCase()) || label.includes('reason') || label.includes('why');
}

function isEvidenceField(field: AppealFormField): boolean {
  const label = field.label.toLowerCase();
  return EVIDENCE_FIELD_IDS.has(field.id.toLowerCase()) || label.includes('evidence') || label.includes('proof');
}

const MessageAvatar = ({ message, playerUuid }: { message: AppealMessage; playerUuid: string }) => {
  const { t } = useTranslation();
  const [avatarError, setAvatarError] = useState(false);
  const [avatarLoading, setAvatarLoading] = useState(true);
  const initials = message.senderName.substring(0, 2);

  if (message.sender === 'staff') {
    return (
      <div className="h-8 w-8 bg-green-100 dark:bg-green-900 rounded-md flex items-center justify-center shrink-0">
        <span className="text-xs font-bold text-green-600 dark:text-green-300">{initials || 'S'}</span>
      </div>
    );
  }

  if (playerUuid !== '' && !avatarError) {
    return (
      <div className="relative h-8 w-8 bg-muted rounded-md flex items-center justify-center overflow-hidden shrink-0">
        <img
          src={getAvatarUrl(playerUuid, 32, true)}
          alt={t('appeals.avatarAlt', { name: message.senderName })}
          className={`w-full h-full object-cover transition-opacity duration-200 ${avatarLoading ? 'opacity-0' : 'opacity-100'}`}
          onError={() => {
            setAvatarError(true);
            setAvatarLoading(false);
          }}
          onLoad={() => {
            setAvatarError(false);
            setAvatarLoading(false);
          }}
        />
        {avatarLoading && (
          <div className="absolute inset-0 flex items-center justify-center">
            <span className="text-xs font-bold text-primary">{initials || 'P'}</span>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="h-8 w-8 bg-blue-100 dark:bg-blue-900 rounded-md flex items-center justify-center shrink-0">
      <span className="text-xs font-bold text-blue-600 dark:text-blue-300">{initials || 'P'}</span>
    </div>
  );
};

const AppealsPage = () => {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const { toast } = useToast();
  const [banInfo, setBanInfo] = useState<PublicPunishmentAppealInfo | null>(null);
  const [appealInfo, setAppealInfo] = useState<AppealInfo | null>(null);
  const [showAppealForm, setShowAppealForm] = useState(false);
  const [isLoadingPunishment, setIsLoadingPunishment] = useState(false);
  const [punishmentError, setPunishmentError] = useState<string | null>(null);
  const [newReply, setNewReply] = useState("");
  const [verificationNeeded, setVerificationNeeded] = useState(false);
  const [verificationEmailHint, setVerificationEmailHint] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const [verificationCodeSent, setVerificationCodeSent] = useState(false);
  const [replyAttachments, setReplyAttachments] = useState<ReplyAttachment[]>([]);
  const [isSendingReply, setIsSendingReply] = useState(false);
  const [appealFormSettings, setAppealFormSettings] = useState<AppealFormSettings | undefined>(undefined);

  const createAppealMutation = useCreateAppeal();
  const requestVerificationMutation = useRequestAppealVerification();
  const emailDelivery = useEmailDelivery();
  const appealEmailPolicy = resolveAppealEmailPolicy(emailDelivery);
  const validEmail = trimmedEmail(t('submitTicket.invalidEmailDesc'));
  const appealEmailSchema = appealEmailPolicy.emailRequired ? validEmail : z.union([z.literal(''), validEmail]).optional();
  const verifyCodeMutation = useVerifyAppealCode();

  const dynamicSchema: z.ZodType<AppealFormValues, AppealFormValues> = z.object({
    ...appealFieldSchemas(appealFormSettings?.fields ?? []),
    banId: banIdSchema,
    email: appealEmailSchema,
  });

  const appealForm = useForm<AppealFormValues>({
    resolver: zodResolver(dynamicSchema),
    defaultValues: {
      banId: "",
      email: "",
      ...appealFieldDefaults(appealFormSettings?.fields ?? []),
    },
  });

  const searchForm = useForm<SearchFormValues>({
    resolver: zodResolver(searchSchema),
    defaultValues: {
      banId: "",
    },
  });

  const defaultAppealForm = (): AppealFormSettings => ({
    fields: [
      {
        id: 'reason',
        type: 'textarea',
        label: t('appeals.appealReason'),
        description: t('appeals.defaultReasonDesc'),
        required: true,
        order: 1,
        sectionId: 'default_section'
      }
    ],
    sections: [
      {
        id: 'default_section',
        title: t('appeals.defaultSectionTitle'),
        order: 0
      }
    ]
  });

  const applyAppealForm = (settings: AppealFormSettings, banId: string) => {
    setAppealFormSettings(settings);
    appealForm.reset({
      banId,
      email: appealForm.getValues('email') ?? '',
      ...appealFieldDefaults(settings.fields),
    });
  };

  const loadAppealDetails = async (appealId: string, fallbackPunishmentId: string) => {
    const appealData = await fetchPublicAppeal(appealId).catch(() => null);
    if (appealData === null) return;
    if ('requiresVerification' in appealData) {
      setVerificationNeeded(true);
      setVerificationEmailHint(appealData.emailHint);
      return;
    }
    setVerificationNeeded(false);
    setAppealInfo({
      id: appealData.id,
      banId: appealData.punishmentId || fallbackPunishmentId,
      submittedOn: appealData.created ?? '',
      status: deriveAppealStatus(appealData.appealWorkflowStatus, appealData.status),
      locked: appealData.locked,
      lastUpdate: appealData.created,
      messages: appealData.messages.map(toAppealMessage),
    });
  };

  const showPunishment = async (info: PublicPunishmentAppealInfo) => {
    setBanInfo(info);
    applyAppealForm(info.appealForm ?? defaultAppealForm(), info.id);

    const existingAppeal = info.existingAppeal;
    if (existingAppeal !== null) {
      const status = deriveAppealStatus(existingAppeal.appealWorkflowStatus, existingAppeal.status);
      setAppealInfo({
        id: existingAppeal.id,
        banId: info.id,
        submittedOn: existingAppeal.submittedDate,
        status,
        locked: existingAppeal.locked,
        lastUpdate: existingAppeal.submittedDate || null,
        messages: []
      });
      setShowAppealForm(false);

      await loadAppealDetails(existingAppeal.id, info.id);
      return;
    }

    setAppealInfo(null);
    if (!info.appealable) {
      toast({
        title: t('appeals.cannotAppeal'),
        description: t('appeals.cannotAppealDesc'),
        variant: "destructive"
      });
      setShowAppealForm(false);
    } else if (!info.active) {
      toast({
        title: t('appeals.cannotAppealInactive'),
        description: t('appeals.cannotAppealInactiveDesc'),
        variant: "destructive"
      });
      setShowAppealForm(false);
    } else {
      setShowAppealForm(true);
    }
  };

  const onSearchSubmit = async (values: SearchFormValues) => {
    const normalizedBanId = values.banId.toUpperCase().replace(/\s/g, '');
    setIsLoadingPunishment(true);
    setPunishmentError(null);
    setVerificationNeeded(false);
    setVerificationCodeSent(false);
    setVerificationCode('');
    setVerificationEmailHint('');

    try {
      const lookup = await fetchPunishmentAppealInfo(normalizedBanId);
      switch (lookup.kind) {
        case 'notStarted':
          setPunishmentError(t('appeals.punishmentNotStarted'));
          break;
        case 'found':
          await showPunishment(lookup.info);
          break;
      }
    } catch {
      setBanInfo(null);
      setAppealInfo(null);
      setShowAppealForm(false);
      setPunishmentError(t('appeals.punishmentNotFound', { id: normalizedBanId }));
    } finally {
      setIsLoadingPunishment(false);
    }
  };

  const onAppealSubmit = (values: AppealFormValues) => {
    if (!banInfo) return;

    const fields = appealFormSettings?.fields ?? [];
    const reasonField = fields.find(isReasonField);
    const evidenceField = fields.find(isEvidenceField);

    const attachments = fields
      .filter((field) => field.type === 'file_upload')
      .flatMap((field) => fileFieldAttachments(values[field.id]));

    const additionalData = Object.fromEntries(
      Object.entries(values).filter((entry): entry is [string, AppealFieldValue] =>
        entry[1] !== undefined &&
        !SYSTEM_FIELD_IDS.has(entry[0]) &&
        entry[0] !== reasonField?.id &&
        entry[0] !== evidenceField?.id
      )
    );

    const fieldLabels = Object.fromEntries(fields.map((field) => [field.id, field.label]));

    createAppealMutation.mutate({
      punishmentId: values.banId,
      playerUuid: banInfo.playerUuid,
      email: typeof values.email === 'string' ? values.email.trim() : '',
      reason: reasonField ? values[reasonField.id] : '',
      evidence: evidenceField ? values[evidenceField.id] : '',
      additionalData,
      attachments,
      fieldLabels,
    }, {
      onSuccess: (created) => {
        toast({
          title: t('appeals.appealSubmitted'),
          description: t('appeals.appealSubmittedDesc', { id: values.banId }),
        });
        setShowAppealForm(false);
        if (created.publicUrl) {
          navigate(toInAppPath(created.publicUrl));
          return;
        }
        void onSearchSubmit({ banId: values.banId });
      },
      onError: () => {
        toast({
          title: t('appeals.error'),
          description: t('appeals.submitFailed'),
          variant: "destructive"
        });
      },
    });
  };

  const handleRequestVerificationCode = () => {
    if (!appealInfo) return;
    requestVerificationMutation.mutate(appealInfo.id, {
      onSuccess: (result) => {
        setVerificationEmailHint(result.emailHint || verificationEmailHint);
        setVerificationCodeSent(true);
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

  const handleVerifyAppealCode = () => {
    if (!appealInfo || verificationCode.length !== 6) return;
    const { id, banId } = appealInfo;
    verifyCodeMutation.mutate({ appealId: id, code: verificationCode }, {
      onSuccess: (result) => {
        if (!result.token) return;
        setVerificationNeeded(false);
        setVerificationCodeSent(false);
        setVerificationCode('');
        toast({
          title: t('playerTicket.verified'),
        });
        void loadAppealDetails(id, banId);
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

  const sendReply = async (appeal: AppealInfo, content: string) => {
    try {
      await postPublicAppealReply(appeal.id, {
        name: 'You',
        content,
        staff: false,
        attachments: replyAttachments.map(replyAttachmentJson),
      });
    } catch (replyError) {
      if (replyError instanceof ApiHttpError && replyError.status === 409) {
        await loadAppealDetails(appeal.id, appeal.banId);
      }
      throw replyError;
    }

    await loadAppealDetails(appeal.id, appeal.banId);
    setNewReply("");
    setReplyAttachments([]);

    toast({
      title: t('appeals.replySent'),
      description: t('appeals.replySentDesc'),
    });
  };

  const handleSendReply = () => {
    if (!newReply.trim() || !appealInfo || isSendingReply) return;
    setIsSendingReply(true);
    sendReply(appealInfo, newReply)
      .catch((error: unknown) => {
        toast({
          title: t('appeals.error'),
          description: isThreadFullError(error) ? t('publicReply.threadFull') : describeError(error, t('appeals.replyFailed')),
          variant: "destructive"
        });
      })
      .finally(() => setIsSendingReply(false));
  };

  const submitSearch = searchForm.handleSubmit(onSearchSubmit);
  const submitAppeal = appealForm.handleSubmit(onAppealSubmit);

  const appealAcceptsReplies = appealInfo !== null && !appealInfo.locked && !isTerminalAppealStatus(appealInfo.status);

  const watchedValues = useWatch({ control: appealForm.control });

  const watchedText = (fieldId: string): string => {
    const value = watchedValues[fieldId];
    return typeof value === 'string' ? value : '';
  };

  const isSectionVisible = (section: AppealFormSection, fields: readonly AppealFormField[]): boolean => {
    let isVisible = section.hideByDefault !== true;

    if (section.showIfFieldId && section.showIfValue) {
      isVisible = watchedText(section.showIfFieldId) === section.showIfValue;
    } else if (section.showIfFieldId && section.showIfValues) {
      isVisible = section.showIfValues.includes(watchedText(section.showIfFieldId));
    }

    if (isVisible) return true;
    return fields.some((field) => field.optionSectionMapping?.[watchedText(field.id)] === section.id);
  };

  const renderFormField = (field: AppealFormField) => {
    switch (field.type) {
      case 'text':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => (
              <FormItem data-modl="form-field">
                <FormLabel>{field.label}</FormLabel>
                <FormControl>
                  <Input {...formField} value={textValue(formField.value)} />
                </FormControl>
                {field.description && (
                  <FormDescription>{field.description}</FormDescription>
                )}
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'textarea':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => (
              <FormItem data-modl="form-field">
                <FormLabel>{field.label}</FormLabel>
                <FormControl>
                  <Textarea
                    {...formField}
                    value={textValue(formField.value)}
                    className="min-h-[100px]"
                  />
                </FormControl>
                {field.description && (
                  <FormDescription>{field.description}</FormDescription>
                )}
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'checkbox':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => (
              <FormItem data-modl="form-field" className="flex flex-row items-start gap-2 space-y-0">
                <FormControl>
                  <Checkbox
                    checked={formField.value === true}
                    onCheckedChange={formField.onChange}
                  />
                </FormControl>
                <div className="space-y-1 leading-none">
                  <FormLabel>{field.label}</FormLabel>
                  {field.description && (
                    <FormDescription>{field.description}</FormDescription>
                  )}
                </div>
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'dropdown':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => (
              <FormItem data-modl="form-field">
                <FormLabel>{field.label}</FormLabel>
                <Select
                  onValueChange={formField.onChange}
                  defaultValue={textValue(formField.value)}
                >
                  <FormControl>
                    <SelectTrigger>
                      <SelectValue placeholder={t('appeals.selectAnOption')} />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {field.options?.map((option) => (
                      <SelectItem key={option} value={option}>
                        {option}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {field.description && (
                  <FormDescription>{field.description}</FormDescription>
                )}
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'multiple_choice':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => (
              <FormItem data-modl="form-field" className="space-y-3">
                <FormLabel>{field.label}</FormLabel>
                <FormControl>
                  <RadioGroup
                    onValueChange={formField.onChange}
                    defaultValue={textValue(formField.value)}
                  >
                    {field.options?.map((option) => (
                      <FormItem key={option} className="flex items-center gap-2 space-y-0">
                        <FormControl>
                          <RadioGroupItem value={option} />
                        </FormControl>
                        <FormLabel className="font-normal">
                          {option}
                        </FormLabel>
                      </FormItem>
                    ))}
                  </RadioGroup>
                </FormControl>
                {field.description && (
                  <FormDescription>{field.description}</FormDescription>
                )}
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'checkboxes':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={() => (
              <FormItem data-modl="form-field">
                <div className="mb-2">
                  <FormLabel>{field.label}</FormLabel>
                  {field.description && (
                    <FormDescription>{field.description}</FormDescription>
                  )}
                </div>
                {field.options?.map((option) => (
                  <FormField
                    key={option}
                    control={appealForm.control}
                    name={field.id}
                    render={({ field: formField }) => (
                      <FormItem
                        key={option}
                        className="flex flex-row items-center gap-2 space-y-0 mb-2"
                      >
                        <FormControl>
                          <Checkbox
                            checked={selectedOptions(formField.value).includes(option)}
                            onCheckedChange={(checked) => {
                              const current = selectedOptions(formField.value);
                              if (checked === true) {
                                formField.onChange([...current, option]);
                              } else {
                                formField.onChange(current.filter((value) => value !== option));
                              }
                            }}
                          />
                        </FormControl>
                        <FormLabel className="font-normal">
                          {option}
                        </FormLabel>
                      </FormItem>
                    )}
                  />
                ))}
                <FormMessage />
              </FormItem>
            )}
          />
        );

      case 'file_upload':
        return (
          <FormField
            key={field.id}
            control={appealForm.control}
            name={field.id}
            render={({ field: formField }) => {
              const currentFiles = uploadedFiles(formField.value);

              const handleFileUpload = (result: { url: string; key: string }, file?: File) => {
                const newFile: AppealFieldFile = {
                  id: Date.now().toString(),
                  url: result.url,
                  key: result.key,
                  fileName: file?.name || lastPathSegment(result.url),
                  fileType: file?.type || OCTET_STREAM,
                  fileSize: file?.size ?? 0
                };

                formField.onChange([...currentFiles, newFile]);
              };

              const handleRemoveFile = (fileToRemove: AppealFieldFile) => {
                formField.onChange(currentFiles.filter((file) => file.id !== fileToRemove.id));
              };

              return (
                <FormItem data-modl="form-field">
                  <FormLabel>{field.label}</FormLabel>
                  <FormControl>
                    <div className="space-y-2">
                      <MediaUpload
                        uploadType="appeal"
                        onUploadComplete={handleFileUpload}
                        metadata={{
                          entityId: 'new',
                          fieldId: field.id
                        }}
                        variant="compact"
                        maxFiles={5}
                      />

                      {currentFiles.length > 0 && (
                        <div className="mt-2 space-y-1">
                          <div className="text-sm font-medium text-muted-foreground">{t('appeals.uploadedFiles')}</div>
                          {currentFiles.map((file) => (
                            <div key={file.id} className="flex items-center justify-between p-2 bg-muted/50 rounded border">
                              <div className="flex items-center gap-2">
                                {fileIcon(file.fileType)}
                                <span className="text-sm">{file.fileName}</span>
                              </div>
                              <button
                                type="button"
                                onClick={() => handleRemoveFile(file)}
                                className="text-destructive hover:text-destructive/80 text-sm"
                              >
                                {t('appeals.remove')}
                              </button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </FormControl>
                  {field.description && (
                    <FormDescription>{field.description}</FormDescription>
                  )}
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        );
    }
  };

  const renderAppealStatusBanner = (status: NormalizedAppealStatus) => {
    switch (status) {
      case 'pending_player_response':
        return (
          <StatusBanner variant="warning" title={t('appeals.replyNeeded')}>
            {t('appeals.replyNeededDesc')}
          </StatusBanner>
        );
      case 'approved':
        return (
          <StatusBanner variant="success" title={t('appeals.appealApproved')}>
            {t('appeals.appealApprovedDesc')}
          </StatusBanner>
        );
      case 'open':
      case 'under_review':
      case 'rejected':
      case 'closed':
        return null;
    }
  };

  const appealStatusBadgeVariant = (status: NormalizedAppealStatus) => {
    switch (status) {
      case 'approved':
        return 'success';
      case 'closed':
        return 'outline';
      case 'rejected':
        return 'destructive';
      case 'open':
      case 'under_review':
      case 'pending_player_response':
        return 'default';
    }
  };

  const formFields = appealFormSettings?.fields ?? [];
  const formSections = appealFormSettings?.sections ?? [];

  return (
    <div className="min-h-screen p-4 sm:p-6">
      <div className="max-w-2xl mx-auto space-y-6">
        <h1 data-modl="page-title" className="text-xl font-semibold">{t('appeals.title')}</h1>

        <Card data-modl="appeal-lookup" className="shadow-card">
          <CardContent className="pt-6">
            <Form {...searchForm}>
              <form data-modl="form" onSubmit={(event) => { void submitSearch(event); }} className="space-y-4">
                <FormField
                  control={searchForm.control}
                  name="banId"
                  render={({ field }) => (
                    <FormItem data-modl="form-field">
                      <FormLabel>{t('appeals.punishmentId')}</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          placeholder={t('appeals.punishmentIdPlaceholder')}
                          className={`font-mono uppercase ${punishmentError ? 'border-destructive' : ''}`}
                          aria-invalid={punishmentError !== null}
                          autoCapitalize="characters"
                          spellCheck={false}
                          disabled={isLoadingPunishment}
                          onChange={(e) => {
                            field.onChange(e);
                            if (punishmentError) setPunishmentError(null);
                          }}
                        />
                      </FormControl>
                      <FormDescription>
                        {t('appeals.punishmentIdDesc')}
                      </FormDescription>
                      <FormMessage />
                      {punishmentError && (
                        <p className="text-sm font-medium text-destructive">{punishmentError}</p>
                      )}
                    </FormItem>
                  )}
                />

                <Button data-modl="submit-button" type="submit" variant={banInfo ? 'outline' : 'default'} className="w-full" disabled={isLoadingPunishment}>
                  {isLoadingPunishment ? t('appeals.searching') : t('appeals.checkStatus')}
                </Button>
              </form>
            </Form>

          </CardContent>
        </Card>

            {banInfo && (
              <Card data-modl="appeal-punishment" className="shadow-card">
                <CardHeader>
                  <CardTitle>{t('appeals.punishmentInformation')}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.punishmentIdLabel')}</span>
                    <span className="font-mono text-sm">{banInfo.id}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.typeLabel')}</span>
                    <span className="text-sm">{banInfo.type}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.dateLabel')}</span>
                    <span className="text-sm">{formatPunishmentDate(banInfo.issued, t('common.unknown'))}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.statusLabel')}</span>
                    <Badge variant={banInfo.active ? "destructive" : "secondary"}>
                      {banInfo.active ? t('status.active') : t('status.expired')}
                    </Badge>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.expires')}</span>
                    <span className="text-sm">{formatPunishmentDate(banInfo.expires, t('punishment.permanent'))}</span>
                  </div>
                </CardContent>
              </Card>
            )}

            {appealInfo && (
              <Card data-modl="appeal-status" className="shadow-card">
                <CardHeader>
                  <CardTitle>{t('appeals.appealStatus')}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                <div className="space-y-3">
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.appealIdLabel')}</span>
                    <span className="font-mono text-sm">{appealInfo.id}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.submittedLabel')}</span>
                    <span className="text-sm">{formatDate(appealInfo.submittedOn)}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-sm text-muted-foreground">{t('appeals.statusLabel')}</span>
                    <Badge variant={appealStatusBadgeVariant(appealInfo.status)}>
                      {t(APPEAL_STATUS_LABEL_KEYS[appealInfo.status])}
                    </Badge>
                  </div>
                  {appealInfo.lastUpdate && (
                    <div className="flex items-center justify-between gap-4">
                      <span className="text-sm text-muted-foreground">{t('appeals.lastUpdateLabel')}</span>
                      <span className="text-sm">{formatDate(appealInfo.lastUpdate)}</span>
                    </div>
                  )}
                </div>

                {renderAppealStatusBanner(appealInfo.status)}

                {verificationNeeded && (
                  <section className="space-y-4 border-t pt-4">
                    <div className="space-y-1">
                      <h2 className="text-base font-semibold">{t('playerTicket.verificationRequired')}</h2>
                      {verificationEmailHint && (
                        <p className="text-sm text-muted-foreground">
                          {t('appeals.verificationRequiredDesc')} {t('playerTicket.codeSentTo', { email: verificationEmailHint })}
                        </p>
                      )}
                    </div>
                      {!verificationEmailHint ? (
                        <PrivateRecordNotice />
                      ) : !verificationCodeSent ? (
                        <Button onClick={handleRequestVerificationCode} disabled={requestVerificationMutation.isPending} className="w-full">
                          {requestVerificationMutation.isPending ? t('playerTicket.sending') : t('playerTicket.sendVerificationCode')}
                        </Button>
                      ) : (
                        <>
                          <div className="space-y-2">
                            <Label htmlFor="appealVerificationCode">{t('playerTicket.enterCode', { email: verificationEmailHint })}</Label>
                            <Input
                              id="appealVerificationCode"
                              type="text"
                              placeholder="000000"
                              value={verificationCode}
                              onChange={(e) => setVerificationCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                              maxLength={6}
                              className="text-center text-lg tracking-widest"
                            />
                          </div>
                          <Button onClick={handleVerifyAppealCode} disabled={verifyCodeMutation.isPending || verificationCode.length !== 6} className="w-full">
                            {verifyCodeMutation.isPending ? t('playerTicket.verifying') : t('playerTicket.verifyCode')}
                          </Button>
                          <Button variant="ghost" size="sm" onClick={handleRequestVerificationCode} disabled={requestVerificationMutation.isPending} className="w-full">
                            {t('playerTicket.resendCode')}
                          </Button>
                        </>
                      )}
                  </section>
                )}

                {appealInfo.messages.length > 0 && (
                  <div className="space-y-4 border-t pt-4">
                    <div>
                      <h2 className="text-base font-semibold">{t('appeals.appealConversation')}</h2>
                      <div data-modl="message-list" className="divide-y max-h-[400px] overflow-y-auto">
                        {appealInfo.messages.map((message) => (
                          <div data-modl="message" data-modl-sender={message.sender} key={message.id} className="py-4">
                            <div className="flex items-start gap-3">
                              <MessageAvatar message={message} playerUuid={banInfo?.playerUuid ?? ''} />
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2 mb-1">
                                  <span className="font-medium text-sm">{message.senderName}</span>
                                  <span className="text-xs text-muted-foreground">
                                    {formatDate(message.timestamp)}
                                  </span>
                                  {message.sender === 'staff' && (
                                    <Badge variant="secondary">
                                      {t('playerTicket.staffBadge')}
                                    </Badge>
                                  )}
                                </div>
                                <div className="text-sm">
                                  <div className="whitespace-pre-wrap wrap-break-word" style={{ whiteSpace: 'pre-wrap' }}>{message.content}</div>

                                  {message.attachments.length > 0 && (
                                    <div className="flex flex-wrap gap-2 mt-2">
                                      {message.attachments.map((attachment) => (
                                        <ChipButton key={attachment.key} onClick={() => openExternalUrl(attachment.url)}>
                                          {fileIcon(attachment.fileType)}
                                          <span>{truncateFileName(attachment.fileName || lastPathSegment(attachment.url))}</span>
                                        </ChipButton>
                                      ))}
                                    </div>
                                  )}
                                </div>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>

                    {appealInfo.locked && !isTerminalAppealStatus(appealInfo.status) && (
                      <StatusBanner variant="info" title={t('appeals.repliesLocked')}>
                        {t('appeals.repliesLockedDesc')}
                      </StatusBanner>
                    )}

                    {appealAcceptsReplies && (
                      <div data-modl="reply" className="border-t pt-4">
                          <div className="space-y-4">
                            <div className="space-y-2">
                              <Label htmlFor="reply">{t('playerTicket.replyLabel')}</Label>
                              <PublicReplyInput
                                id="reply"
                                value={newReply}
                                onChange={setNewReply}
                              />
                            </div>

                            {replyAttachments.length > 0 && (
                              <div className="flex flex-wrap gap-2">
                                {replyAttachments.map((attachment) => (
                                  <Chip key={attachment.id}>
                                    <ChipAction onClick={() => openExternalUrl(attachment.url)}>
                                      {fileIcon(attachment.fileType)}
                                      <span>{truncateFileName(attachment.fileName)}</span>
                                    </ChipAction>
                                    <ChipRemoveButton
                                      label={t('appeals.removeFile', { name: attachment.fileName })}
                                      onClick={() => {
                                        setReplyAttachments(prev => prev.filter(a => a.id !== attachment.id));
                                      }}
                                    />
                                  </Chip>
                                ))}
                              </div>
                            )}

                            <div className="flex items-center justify-between">
                              <MediaUpload
                                uploadType="appeal"
                                onUploadComplete={(result, file) => {
                                  if (file) {
                                    const newAttachment: ReplyAttachment = {
                                      id: Date.now().toString(),
                                      url: result.url,
                                      key: result.key,
                                      fileName: file.name,
                                      fileType: file.type,
                                      fileSize: file.size,
                                      uploadedAt: new Date().toISOString(),
                                      uploadedBy: 'You'
                                    };
                                    setReplyAttachments(prev => [...prev, newAttachment]);
                                  }
                                }}
                                metadata={{
                                  appealId: appealInfo.id,
                                  fieldId: 'reply'
                                }}
                                variant="button-only"
                                maxFiles={5}
                              />
                              <Button
                                data-modl="submit-button"
                                onClick={handleSendReply}
                                disabled={!newReply.trim() || isSendingReply}
                              >
                                {t('playerTicket.sendReply')}
                              </Button>
                            </div>
                          </div>
                      </div>
                    )}
                  </div>
                )}
                </CardContent>
              </Card>
            )}

            {showAppealForm && banInfo && (
              <>
                {!banInfo.appealable && (
                  <StatusBanner variant="error" title={t('appeals.appealNotAvailable')}>
                    {t('appeals.appealNotAvailableDesc')}
                  </StatusBanner>
                )}

                {banInfo.appealable && (
                  <Card data-modl="form-card" className="shadow-card">
                    <CardHeader>
                      <CardTitle data-modl="form-title">{t('appeals.submitAppeal')}</CardTitle>
                    </CardHeader>
                    <CardContent>
                  <Form {...appealForm}>
                    <form data-modl="form" onSubmit={(event) => { void submitAppeal(event); }} className="space-y-4">
                      {appealEmailPolicy.showEmailField && (
                        <FormField
                          control={appealForm.control}
                          name="email"
                          render={({ field }) => (
                            <FormItem data-modl="form-field">
                              <FormLabel>{t('submitTicket.emailAddress')}</FormLabel>
                              <FormControl>
                                <Input {...field} type="email" placeholder={t('appeals.emailPlaceholder')} />
                              </FormControl>
                              <FormDescription>{t('appeals.emailDesc')}</FormDescription>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                      )}

                      {byOrder(formSections)
                        .filter((section) => isSectionVisible(section, formFields))
                        .map((section) => (
                          <section data-modl="form-section" key={section.id} className="space-y-4 border-t pt-4">
                            <div>
                              <h3 className="text-base font-semibold">{section.title}</h3>
                              {section.description && (
                                <p className="text-sm text-muted-foreground mt-1">{section.description}</p>
                              )}
                            </div>
                            {byOrder(formFields.filter((field) => field.sectionId === section.id)).map(renderFormField)}
                          </section>
                        ))}

                      {byOrder(formFields.filter((field) => !field.sectionId)).map(renderFormField)}

                      {formFields.length === 0 && (
                        <FormField
                          control={appealForm.control}
                          name={FALLBACK_REASON_FIELD_ID}
                          render={({ field }) => (
                            <FormItem data-modl="form-field">
                              <FormLabel>{t('appeals.appealReason')}</FormLabel>
                              <FormControl>
                                <Textarea
                                  {...field}
                                  value={textValue(field.value)}
                                  placeholder={t('appeals.appealReasonPlaceholder')}
                                  className="min-h-[120px]"
                                />
                              </FormControl>
                              <FormDescription>
                                {t('appeals.appealReasonDesc')}
                              </FormDescription>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                      )}

                    </form>
                  </Form>
                    </CardContent>
                    <CardFooter>
                      <Button
                        data-modl="submit-button"
                        type="submit"
                        className="w-full"
                        disabled={createAppealMutation.isPending}
                        onClick={(event) => { void submitAppeal(event); }}
                      >
                        {createAppealMutation.isPending ? t('playerTicket.submitting') : t('appeals.submitAppeal')}
                      </Button>
                    </CardFooter>
                  </Card>
                )}
              </>
            )}
      </div>
    </div>
  );
};

export default AppealsPage;
