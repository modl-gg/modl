import { z } from 'zod';
import type { JsonObject } from '@bufbuild/protobuf';
import type { TicketFormField, TicketFormSection, TicketFormSettings } from '@/types/forms';
import { byOrder, formFieldSchema, formSectionSchema, validEntries } from '@/lib/form-definition';
import { optionalField } from '@/lib/defined-fields';
import { isValidEmail, normalizeEmail } from '@/utils/email-validation';
import { isEmailAuthSelected, type TicketEmailPolicy } from '@/utils/ticket-email-policy';

export const EMAIL_FIELD_ID = 'email';
export const EMAIL_AUTH_FIELD_ID = 'emailAuthEnabled';

export type TicketFormData = Readonly<Record<string, string>>;
export type TicketPendingFiles = Readonly<Record<string, readonly File[]>>;

export interface TicketFormFileAttachment {
  url: string;
  key: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  fieldId: string;
  fieldLabel: string;
}

export type TicketFormUploader = (file: File, field: TicketFormField) => Promise<{ url: string; key: string }>;

const TICKET_FIELD_TYPES: Readonly<Record<TicketFormField['type'], true>> = {
  text: true,
  textarea: true,
  dropdown: true,
  multiple_choice: true,
  checkbox: true,
  file_upload: true,
  checkboxes: true,
  description: true,
};

const optionalFlag = z.boolean().optional().catch(undefined);

const ticketFormFieldSchema = formFieldSchema(TICKET_FIELD_TYPES);

const receivedTicketFormSchema = z.object({
  fields: z.array(z.unknown()),
  sections: z.array(z.unknown()).catch([]),
  requireEmail: optionalFlag,
  requireEmailAuth: optionalFlag,
  allowEmailNotifications: optionalFlag,
});

const ticketFormsSchema = z.record(z.string(), z.unknown());

function parseTicketFormSettings(value: unknown): TicketFormSettings | null {
  const form = receivedTicketFormSchema.safeParse(value);
  if (!form.success) return null;
  return {
    ...optionalField('requireEmail', form.data.requireEmail),
    ...optionalField('requireEmailAuth', form.data.requireEmailAuth),
    ...optionalField('allowEmailNotifications', form.data.allowEmailNotifications),
    fields: validEntries<TicketFormField>(ticketFormFieldSchema, form.data.fields),
    sections: validEntries<TicketFormSection>(formSectionSchema, form.data.sections),
  };
}

export function isApplicationTicketType(ticketType: string): boolean {
  const normalized = ticketType.toLowerCase();
  return normalized === 'staff' || normalized === 'application';
}

export function ticketFormFor(ticketForms: unknown, ticketType: string): TicketFormSettings | null {
  const forms = ticketFormsSchema.safeParse(ticketForms).data ?? {};
  const candidates = [
    forms[ticketType.toLowerCase()],
    forms[ticketType],
    isApplicationTicketType(ticketType) ? forms['application'] : undefined,
  ];
  for (const candidate of candidates) {
    const form = parseTicketFormSettings(candidate);
    if (form !== null) return form;
  }
  return null;
}

export function insertFieldAtOrder(fields: readonly TicketFormField[], inserted: TicketFormField): TicketFormField[] {
  const shifted = fields.map((field) => (field.order >= 1 ? { ...field, order: field.order + inserted.order } : field));
  return byOrder([inserted, ...shifted]);
}

export function selectedCheckboxValues(value: string | undefined): string[] {
  return (value ?? '').split(',').map((option) => option.trim()).filter((option) => option !== '');
}

function mappedSections(field: TicketFormField, value: string): (string | undefined)[] {
  const mapping = field.optionSectionMapping;
  if (mapping === undefined || value === '') return [];
  switch (field.type) {
    case 'dropdown':
    case 'multiple_choice':
      return [mapping[value]];
    case 'checkbox':
      return [mapping[value] || (value === 'true' ? mapping['true'] || mapping['checked'] : undefined)];
    case 'checkboxes':
      return selectedCheckboxValues(value).map((option) => mapping[option]);
    case 'text':
    case 'textarea':
    case 'file_upload':
    case 'description':
      return [];
  }
}

function isConditionalSectionShown(section: TicketFormSection, formData: TicketFormData): boolean {
  if (section.showIfFieldId === undefined || section.showIfFieldId === '') return false;
  const triggerValue = formData[section.showIfFieldId];
  if (triggerValue === undefined) return false;
  const matchesValue = section.showIfValue !== undefined && section.showIfValue !== '' && triggerValue === section.showIfValue;
  return matchesValue || (section.showIfValues?.includes(triggerValue) ?? false);
}

export function visibleTicketSections(
  form: TicketFormSettings,
  formData: TicketFormData,
): ReadonlySet<string> {
  const visible = new Set<string>();
  for (const section of form.sections) {
    const unconditional = !section.showIfFieldId && section.hideByDefault !== true;
    if (unconditional || isConditionalSectionShown(section, formData)) {
      visible.add(section.id);
    }
  }
  for (const field of form.fields) {
    for (const sectionId of mappedSections(field, formData[field.id] ?? '')) {
      if (sectionId) visible.add(sectionId);
    }
  }
  return visible;
}

function isFieldVisible(field: TicketFormField, visibleSections: ReadonlySet<string>): boolean {
  return !field.sectionId || visibleSections.has(field.sectionId);
}

export function firstMissingRequiredField(
  fields: readonly TicketFormField[],
  visibleSections: ReadonlySet<string>,
  formData: TicketFormData,
): TicketFormField | undefined {
  return fields.find((field) =>
    field.type !== 'description'
    && field.required
    && isFieldVisible(field, visibleSections)
    && (formData[field.id] ?? '').trim() === '');
}

export function ticketFormSubmission(fields: readonly TicketFormField[], formData: TicketFormData) {
  const fieldsById = new Map(fields.map((field) => [field.id, field]));
  const values: Record<string, string> = {};
  const fieldLabels: Record<string, string> = {};
  for (const [fieldId, value] of Object.entries(formData)) {
    const field = fieldsById.get(fieldId);
    if (field?.type === 'file_upload') continue;
    values[fieldId] = value;
    if (field?.label) fieldLabels[fieldId] = field.label;
  }
  return { formData: values, fieldLabels };
}

export function visibleFileUploadFields(
  fields: readonly TicketFormField[],
  visibleSections: ReadonlySet<string>,
): TicketFormField[] {
  return fields.filter((field) => field.type === 'file_upload' && isFieldVisible(field, visibleSections));
}

export function hiddenPendingFileCount(pendingFiles: TicketPendingFiles, uploadFields: readonly TicketFormField[]): number {
  const visibleIds = new Set(uploadFields.map((field) => field.id));
  return Object.entries(pendingFiles)
    .filter(([fieldId]) => !visibleIds.has(fieldId))
    .reduce((count, [, files]) => count + files.length, 0);
}

export async function uploadTicketFormFiles(
  uploadFields: readonly TicketFormField[],
  pendingFiles: TicketPendingFiles,
  upload: TicketFormUploader,
): Promise<TicketFormFileAttachment[]> {
  const attachments: TicketFormFileAttachment[] = [];
  for (const field of uploadFields) {
    for (const file of pendingFiles[field.id] ?? []) {
      const result = await upload(file, field);
      attachments.push({
        url: result.url,
        key: result.key,
        fileName: file.name,
        fileType: file.type || 'application/octet-stream',
        fileSize: file.size,
        fieldId: field.id,
        fieldLabel: field.label,
      });
    }
  }
  return attachments;
}

export type TicketFormValidation =
  | { kind: 'valid'; visibleSections: ReadonlySet<string>; email: string | null; emailAuthSelected: boolean }
  | { kind: 'missingField'; field: TicketFormField }
  | { kind: 'invalidEmail' }
  | { kind: 'emailAuthNeedsEmail' };

export function validateTicketForm(
  form: TicketFormSettings,
  formData: TicketFormData,
  emailPolicy: TicketEmailPolicy,
  emailField: TicketFormField,
): TicketFormValidation {
  const visibleSections = visibleTicketSections(form, formData);
  const fieldsToValidate = emailPolicy.showEmailField ? [emailField, ...form.fields] : form.fields;
  const missingField = firstMissingRequiredField(fieldsToValidate, visibleSections, formData);
  if (missingField !== undefined) return { kind: 'missingField', field: missingField };

  const email = emailPolicy.showEmailField ? normalizeEmail(formData[emailField.id] ?? '') : null;
  const emailAuthSelected = isEmailAuthSelected(emailPolicy, formData[EMAIL_AUTH_FIELD_ID] === 'true');
  if (emailPolicy.emailRequired && !isValidEmail(email)) return { kind: 'invalidEmail' };
  if (emailAuthSelected && !isValidEmail(email)) return { kind: 'emailAuthNeedsEmail' };
  return { kind: 'valid', visibleSections, email, emailAuthSelected };
}

export function ticketEmailField(label: string, description: string, required: boolean, order: number): TicketFormField {
  return { id: EMAIL_FIELD_ID, type: 'text', label, description, required, order };
}

export function ticketFormFileAttachmentJson(attachment: TicketFormFileAttachment): JsonObject {
  return {
    url: attachment.url,
    key: attachment.key,
    fileName: attachment.fileName,
    fileType: attachment.fileType,
    fileSize: attachment.fileSize,
    fieldId: attachment.fieldId,
    fieldLabel: attachment.fieldLabel,
  };
}

export interface TicketValidationProblem {
  titleKey: string;
  descriptionKey: string;
  values?: Readonly<Record<string, string>>;
}

export function ticketValidationProblem(validation: TicketFormValidation): TicketValidationProblem | null {
  switch (validation.kind) {
    case 'missingField':
      return {
        titleKey: 'submitTicket.requiredFieldMissing',
        descriptionKey: 'submitTicket.requiredFieldMissingDesc',
        values: { label: validation.field.label },
      };
    case 'invalidEmail':
      return { titleKey: 'submitTicket.invalidEmail', descriptionKey: 'submitTicket.invalidEmailDesc' };
    case 'emailAuthNeedsEmail':
      return { titleKey: 'submitTicket.emailAuthRequiresEmail', descriptionKey: 'submitTicket.emailAuthRequiresEmailDesc' };
    case 'valid':
      return null;
  }
}

export function fieldsWithEmail(form: TicketFormSettings, emailPolicy: TicketEmailPolicy, emailField: TicketFormField): TicketFormField[] {
  return emailPolicy.showEmailField ? insertFieldAtOrder(form.fields, emailField) : form.fields;
}

export interface PreparedTicketSubmission {
  formData: Record<string, string>;
  fieldLabels: Record<string, string>;
  attachments: JsonObject[];
  hiddenExcludedFiles: number;
}

export async function prepareTicketSubmission(
  form: TicketFormSettings,
  formData: TicketFormData,
  visibleSections: ReadonlySet<string>,
  pendingFiles: TicketPendingFiles,
  upload: TicketFormUploader,
): Promise<PreparedTicketSubmission> {
  const submission = ticketFormSubmission(form.fields, formData);
  const uploadFields = visibleFileUploadFields(form.fields, visibleSections);
  const attachments = await uploadTicketFormFiles(uploadFields, pendingFiles, upload);
  return {
    ...submission,
    attachments: attachments.map(ticketFormFileAttachmentJson),
    hiddenExcludedFiles: hiddenPendingFileCount(pendingFiles, uploadFields),
  };
}
