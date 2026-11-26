export const FORM_FIELD_LABEL_MAX_LENGTH = 1000;
export const FORM_DESCRIPTION_MAX_LENGTH = 2000;
export const FORM_OPTION_MAX_LENGTH = 256;
export const FORM_SECTION_TITLE_MAX_LENGTH = 256;

export interface AppealFormField {
  id: string;
  type: 'text' | 'textarea' | 'dropdown' | 'multiple_choice' | 'checkbox' | 'file_upload' | 'checkboxes';
  label: string;
  description?: string | undefined;
  required: boolean;
  options?: string[] | undefined;
  order: number;
  sectionId?: string | undefined;
  goToSection?: string | undefined;
  optionSectionMapping?: Record<string, string> | undefined;
}

export interface FormSection {
  id: string;
  title: string;
  description?: string | undefined;
  order: number;
  showIfFieldId?: string | undefined;
  showIfValue?: string | undefined;
  showIfValues?: string[] | undefined;
  hideByDefault?: boolean | undefined;
}

export type AppealFormSection = FormSection;

export interface AppealFormSettings {
  fields: AppealFormField[];
  sections: AppealFormSection[];
}

export interface TicketFormField {
  id: string;
  type: 'text' | 'textarea' | 'dropdown' | 'multiple_choice' | 'checkbox' | 'file_upload' | 'checkboxes' | 'description';
  label: string;
  description?: string | undefined;
  required: boolean;
  options?: string[] | undefined;
  order: number;
  sectionId?: string | undefined;
  goToSection?: string | undefined;
  optionSectionMapping?: Record<string, string> | undefined;
}

export type TicketFormSection = FormSection;

export interface TicketFormSettings {
  requireEmail?: boolean;
  requireEmailAuth?: boolean;
  allowEmailNotifications?: boolean;
  fields: TicketFormField[];
  sections: TicketFormSection[];
}

export interface TicketFormsConfiguration {
  bug: TicketFormSettings;
  support: TicketFormSettings;
  application: TicketFormSettings;
}

export type TicketFormType = keyof TicketFormsConfiguration;

export const TICKET_FORM_TYPES: readonly TicketFormType[] = ['bug', 'support', 'application'];
