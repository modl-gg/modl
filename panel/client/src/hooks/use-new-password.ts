import { useReducer } from 'react';
import {
  EMPTY_NEW_PASSWORD_ENTRY,
  isNewPasswordEntryReady,
  nextNewPasswordEntry,
  type NewPasswordEntry,
  type NewPasswordEvent,
} from '@/lib/new-password-entry';

export interface NewPasswordFieldsControl {
  entry: NewPasswordEntry;
  dispatch: (event: NewPasswordEvent) => void;
}

interface NewPassword {
  fields: NewPasswordFieldsControl;
  password: string;
  ready: boolean;
  clear: () => void;
}

export function useNewPassword(): NewPassword {
  const [entry, dispatch] = useReducer(nextNewPasswordEntry, EMPTY_NEW_PASSWORD_ENTRY);

  return {
    fields: { entry, dispatch },
    password: entry.password,
    ready: isNewPasswordEntryReady(entry),
    clear: () => { dispatch({ type: 'cleared' }); },
  };
}
