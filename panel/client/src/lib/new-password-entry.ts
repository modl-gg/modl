import { isNewPasswordReady, passwordLength, passwordProblem, type PasswordProblem } from './password-policy.ts';

export interface NewPasswordEntry {
  password: string;
  confirmation: string;
  passwordChecked: boolean;
  confirmationChecked: boolean;
  confirmationFocused: boolean;
}

export type NewPasswordEvent =
  | { type: 'password-changed'; value: string }
  | { type: 'password-left' }
  | { type: 'confirmation-changed'; value: string }
  | { type: 'confirmation-entered' }
  | { type: 'confirmation-left' }
  | { type: 'enter-pressed' }
  | { type: 'cleared' };

export const EMPTY_NEW_PASSWORD_ENTRY: NewPasswordEntry = {
  password: '',
  confirmation: '',
  passwordChecked: false,
  confirmationChecked: false,
  confirmationFocused: false,
};

export function nextNewPasswordEntry(entry: NewPasswordEntry, event: NewPasswordEvent): NewPasswordEntry {
  switch (event.type) {
    case 'password-changed':
      return { ...entry, password: event.value, passwordChecked: false, confirmationChecked: false };
    case 'password-left':
      return {
        ...entry,
        passwordChecked: entry.passwordChecked || entry.password !== '',
        confirmationChecked: entry.confirmationChecked || entry.confirmation !== '',
      };
    case 'confirmation-changed':
      return { ...entry, confirmation: event.value, confirmationChecked: false };
    case 'confirmation-entered':
      return { ...entry, confirmationFocused: true };
    case 'confirmation-left':
      return {
        ...entry,
        confirmationFocused: false,
        confirmationChecked: entry.confirmationChecked || entry.confirmation !== '',
      };
    case 'enter-pressed':
      return { ...entry, passwordChecked: true, confirmationChecked: true };
    case 'cleared':
      return EMPTY_NEW_PASSWORD_ENTRY;
  }
}

export function shownPasswordProblem(entry: NewPasswordEntry): PasswordProblem | null {
  return entry.passwordChecked ? passwordProblem(entry.password) : null;
}

function confirmationStillBeingTyped(entry: NewPasswordEntry): boolean {
  return entry.confirmationFocused && passwordLength(entry.confirmation) < passwordLength(entry.password);
}

export function isMismatchShown(entry: NewPasswordEntry): boolean {
  return entry.confirmationChecked
    && entry.confirmation !== entry.password
    && !confirmationStillBeingTyped(entry);
}

export function isNewPasswordEntryReady(entry: NewPasswordEntry): boolean {
  return isNewPasswordReady(entry.password, entry.confirmation);
}
