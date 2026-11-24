import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import MediaUpload from '@/components/MediaUpload';
import { byOrder } from '@/lib/form-definition';
import {
  EMAIL_AUTH_FIELD_ID,
  EMAIL_FIELD_ID,
  selectedCheckboxValues,
  visibleTicketSections,
  type TicketFormData,
  type TicketPendingFiles,
} from '@/lib/ticket-form';
import type { TicketFormField, TicketFormSettings } from '@/types/forms';

const TALL_APPLICATION_FIELD_IDS: ReadonlySet<string> = new Set(['introduction', 'server_perspective', 'passion']);

interface TicketFieldProps {
  field: TicketFormField;
  formData: TicketFormData;
  onFieldChange: (fieldId: string, value: string) => void;
  pendingFiles: TicketPendingFiles;
  onFileSelected: (fieldId: string, file: File) => void;
  uploadMetadata: Readonly<Record<string, unknown>>;
  isApplication: boolean;
}

const RequiredMarker = ({ required }: { required: boolean }) =>
  required ? <span className="text-destructive-text ml-1">*</span> : null;

const TicketFieldInput = ({ field, formData, onFieldChange, pendingFiles, onFileSelected, uploadMetadata, isApplication }: TicketFieldProps) => {
  const { t } = useTranslation();
  const value = formData[field.id] ?? '';

  switch (field.type) {
    case 'textarea':
      return (
        <Textarea
          id={field.id}
          value={value}
          onChange={(e) => onFieldChange(field.id, e.target.value)}
          className={isApplication && TALL_APPLICATION_FIELD_IDS.has(field.id) ? "min-h-[180px]" : "min-h-[120px]"}
          required={field.required}
        />
      );
    case 'dropdown':
      return (
        <Select value={value} onValueChange={(selected) => onFieldChange(field.id, selected)}>
          <SelectTrigger>
            <SelectValue placeholder={t('submitTicket.selectField', { label: field.label })} />
          </SelectTrigger>
          <SelectContent>
            {field.options?.map((option) => (
              <SelectItem key={option} value={option}>
                {option}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      );
    case 'checkbox':
      return (
        <div className="flex items-start gap-2">
          <Checkbox
            id={field.id}
            checked={value === "true"}
            onCheckedChange={(checked) => onFieldChange(field.id, checked === true ? "true" : "false")}
            required={field.required}
            className="mt-0.5"
          />
          <label
            htmlFor={field.id}
            className="text-sm font-normal leading-tight peer-disabled:cursor-not-allowed peer-disabled:opacity-70"
          >
            {field.label}
            <RequiredMarker required={field.required} />
          </label>
        </div>
      );
    case 'multiple_choice':
      return (
        <RadioGroup value={value} onValueChange={(selected) => onFieldChange(field.id, selected)} aria-label={field.label}>
          {field.options?.map((option) => (
            <div key={option} className="flex items-center gap-2">
              <RadioGroupItem id={`${field.id}-${option}`} value={option} />
              <label htmlFor={`${field.id}-${option}`} className="text-sm leading-tight cursor-pointer">
                {option}
              </label>
            </div>
          ))}
        </RadioGroup>
      );
    case 'checkboxes': {
      const selected = selectedCheckboxValues(value);
      return (
        <div role="group" aria-label={field.label} className="grid gap-2">
          {field.options?.map((option) => (
            <div key={option} className="flex items-center gap-2">
              <Checkbox
                id={`${field.id}-${option}`}
                checked={selected.includes(option)}
                onCheckedChange={(checked) => {
                  const next = checked === true
                    ? [...selected, option]
                    : selected.filter((current) => current !== option);
                  onFieldChange(field.id, next.join(','));
                }}
              />
              <label
                htmlFor={`${field.id}-${option}`}
                className="text-sm leading-tight cursor-pointer"
              >
                {option}
              </label>
            </div>
          ))}
        </div>
      );
    }
    case 'file_upload': {
      const pendingFile = pendingFiles[field.id]?.[0];
      return (
        <div className="space-y-2">
          <MediaUpload
            uploadType="ticket"
            autoUpload={false}
            onFilesSelected={(files) => {
              const chosen = files[0];
              if (chosen) onFileSelected(field.id, chosen);
            }}
            metadata={{ ...uploadMetadata, fieldId: field.id }}
            variant="compact"
            maxFiles={1}
          />
          {pendingFile ? (
            <div className="text-sm text-muted-foreground">
              {t('playerTicket.selectedFile', { name: pendingFile.name })}
            </div>
          ) : null}
        </div>
      );
    }
    case 'text':
    case 'description':
      return (
        <Input
          id={field.id}
          type={field.id === EMAIL_FIELD_ID ? 'email' : 'text'}
          value={value}
          onChange={(e) => onFieldChange(field.id, e.target.value)}
          required={field.required}
        />
      );
  }
};

const TicketField = (props: TicketFieldProps) => {
  const { field } = props;

  if (field.type === 'description') {
    return (
      <div data-modl="form-field" className="space-y-1">
        {field.label && (
          <Label className="font-medium">{field.label}</Label>
        )}
        {field.description && (
          <p className="text-sm text-muted-foreground">{field.description}</p>
        )}
      </div>
    );
  }

  return (
    <div data-modl="form-field" className="space-y-1">
      {field.type !== 'checkbox' ? (
        <Label htmlFor={field.id} className="font-medium">
          {field.label}
          <RequiredMarker required={field.required} />
        </Label>
      ) : null}

      {field.description && (
        <p className="text-sm text-muted-foreground mb-1">{field.description}</p>
      )}

      <TicketFieldInput {...props} />
    </div>
  );
};

interface TicketFormBodyProps {
  form: TicketFormSettings;
  formData: TicketFormData;
  onFieldChange: (fieldId: string, value: string) => void;
  pendingFiles: TicketPendingFiles;
  onFileSelected: (fieldId: string, file: File) => void;
  uploadMetadata: Readonly<Record<string, unknown>>;
  isApplication: boolean;
  emailAuthOptional: boolean;
  isSubmitting: boolean;
  onSubmit: () => void;
  leadingFields?: ReactNode;
}

export function TicketFormBody({
  form,
  formData,
  onFieldChange,
  pendingFiles,
  onFileSelected,
  uploadMetadata,
  isApplication,
  emailAuthOptional,
  isSubmitting,
  onSubmit,
  leadingFields,
}: TicketFormBodyProps) {
  const { t } = useTranslation();
  const visibleSections = visibleTicketSections(form, formData);
  const fieldProps = { formData, onFieldChange, pendingFiles, onFileSelected, uploadMetadata, isApplication };
  const unsectionedFields = byOrder(form.fields.filter((field) => !field.sectionId));

  return (
    <form
      data-modl="form"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      className="space-y-6"
    >
      <div className="space-y-4">
        {leadingFields}

        {unsectionedFields.map((field) => <TicketField key={field.id} field={field} {...fieldProps} />)}

        {byOrder(form.sections)
          .filter((section) => visibleSections.has(section.id))
          .map((section) => {
            const sectionFields = byOrder(form.fields.filter((field) => field.sectionId === section.id));
            if (sectionFields.length === 0) return null;

            return (
              <section data-modl="form-section" key={section.id} className="space-y-4 border-t pt-4">
                <div>
                  <h3 className="text-base font-semibold">{section.title}</h3>
                  {section.description && (
                    <p className="text-sm text-muted-foreground mt-1">{section.description}</p>
                  )}
                </div>
                {sectionFields.map((field) => <TicketField key={field.id} field={field} {...fieldProps} />)}
              </section>
            );
          })}

        {emailAuthOptional && (
          <div data-modl="form-field" className="border-t pt-4">
            <div className="flex items-start gap-2">
              <Checkbox
                id={EMAIL_AUTH_FIELD_ID}
                checked={formData[EMAIL_AUTH_FIELD_ID] === 'true'}
                onCheckedChange={(checked) => onFieldChange(EMAIL_AUTH_FIELD_ID, checked === true ? 'true' : 'false')}
                className="mt-0.5"
              />
              <div>
                <label htmlFor={EMAIL_AUTH_FIELD_ID} className="text-sm font-medium leading-tight cursor-pointer">
                  {t('submitTicket.enableEmailAuth')}
                </label>
                <p className="text-xs text-muted-foreground mt-1">
                  {t('submitTicket.enableEmailAuthDesc')}
                </p>
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="flex justify-end">
        <Button
          data-modl="submit-button"
          type="submit"
          disabled={isSubmitting}
          className="flex items-center"
        >
          {isSubmitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          {isSubmitting ? t('submitTicket.submitting') : t('submitTicket.submitTicket')}
        </Button>
      </div>
    </form>
  );
}

export function TicketSubjectInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const { t } = useTranslation();
  return (
    <div data-modl="form-field">
      <Label htmlFor="subject" className="font-medium">
        {t('submitTicket.ticketSubject')}
        <RequiredMarker required />
      </Label>
      <Input
        id="subject"
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1"
        required
      />
    </div>
  );
}
