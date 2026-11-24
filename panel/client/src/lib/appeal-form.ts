import { z } from 'zod';
import type { AppealFormField, AppealFormSection, AppealFormSettings } from '@/types/forms';
import { formFieldSchema, formSectionSchema, validEntries } from '@/lib/form-definition';

const appealFieldFileSchema = z.object({
  id: z.string(),
  url: z.string(),
  key: z.string(),
  fileName: z.string(),
  fileType: z.string(),
  fileSize: z.number(),
});

export type AppealFieldFile = z.infer<typeof appealFieldFileSchema>;

export type AppealFieldValue = string | boolean | string[] | AppealFieldFile[];

export type AppealFormValues = Record<string, AppealFieldValue | undefined> & {
  banId: string;
  email?: string | undefined;
};

export type AppealFieldSchema = z.ZodType<AppealFieldValue | undefined, AppealFieldValue | undefined>;

const APPEAL_FIELD_TYPES: Readonly<Record<AppealFormField['type'], true>> = {
  text: true,
  textarea: true,
  dropdown: true,
  multiple_choice: true,
  checkbox: true,
  file_upload: true,
  checkboxes: true,
};

const appealFormFieldSchema = formFieldSchema(APPEAL_FIELD_TYPES);

const receivedAppealFormSchema = z.object({
  fields: z.unknown(),
  sections: z.unknown(),
});

export function parseAppealFormSettings(received: unknown): AppealFormSettings {
  const form = receivedAppealFormSchema.safeParse(received).data;
  return {
    fields: validEntries<AppealFormField>(appealFormFieldSchema, form?.fields),
    sections: validEntries<AppealFormSection>(formSectionSchema, form?.sections),
  };
}

export const FALLBACK_REASON_FIELD_ID = 'reason';

function textSchema(field: AppealFormField, minLength: number, error: string): AppealFieldSchema {
  return field.required ? z.string().min(minLength, { error }) : z.string().optional();
}

function appealFieldSchema(field: AppealFormField): AppealFieldSchema {
  switch (field.type) {
    case 'text':
    case 'dropdown':
    case 'multiple_choice':
      return textSchema(field, 1, `${field.label} is required`);
    case 'textarea':
      return textSchema(field, 10, `${field.label} must be at least 10 characters`);
    case 'checkbox':
      return field.required
        ? z.boolean().refine((checked) => checked, { error: `${field.label} must be checked` })
        : z.boolean().optional();
    case 'checkboxes':
      return field.required
        ? z.array(z.string()).min(1, { error: `At least one ${field.label} must be selected` })
        : z.array(z.string()).optional();
    case 'file_upload':
      return field.required
        ? z.union([
            z.array(appealFieldFileSchema).min(1, { error: `${field.label} is required` }),
            z.string().min(1, { error: `${field.label} is required` }),
          ])
        : z.union([z.array(appealFieldFileSchema), z.string()]).optional();
  }
}

function appealFieldDefault(field: AppealFormField): AppealFieldValue {
  switch (field.type) {
    case 'checkbox':
      return false;
    case 'checkboxes':
      return [];
    case 'text':
    case 'textarea':
    case 'dropdown':
    case 'multiple_choice':
    case 'file_upload':
      return '';
  }
}

export function appealFieldSchemas(fields: readonly AppealFormField[]): Record<string, AppealFieldSchema> {
  if (fields.length === 0) {
    return { [FALLBACK_REASON_FIELD_ID]: z.string().min(20, { error: "Please provide a detailed explanation (min 20 characters)" }) };
  }
  return Object.fromEntries(fields.map((field) => [field.id, appealFieldSchema(field)]));
}

export function appealFieldDefaults(fields: readonly AppealFormField[]): Record<string, AppealFieldValue> {
  if (fields.length === 0) {
    return { [FALLBACK_REASON_FIELD_ID]: '' };
  }
  return Object.fromEntries(fields.map((field) => [field.id, appealFieldDefault(field)]));
}

export function textValue(value: AppealFieldValue | undefined): string {
  return typeof value === 'string' ? value : '';
}

export function selectedOptions(value: AppealFieldValue | undefined): string[] {
  return Array.isArray(value) ? value.filter((option): option is string => typeof option === 'string') : [];
}

export function uploadedFiles(value: AppealFieldValue | undefined): AppealFieldFile[] {
  return Array.isArray(value) ? value.filter((file): file is AppealFieldFile => typeof file !== 'string') : [];
}
