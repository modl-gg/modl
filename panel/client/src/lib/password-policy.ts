export const PASSWORD_MIN_CODE_POINTS = 12;
export const PASSWORD_MAX_CODE_POINTS = 128;

export type PasswordProblem = 'too-short' | 'too-long';

export function passwordLength(value: string): number {
  return Array.from(value.normalize('NFKC')).length;
}

export function isNewPasswordReady(password: string, confirmation: string): boolean {
  return passwordProblem(password) === null && password === confirmation;
}

export function passwordProblem(value: string): PasswordProblem | null {
  const length = passwordLength(value);
  if (length < PASSWORD_MIN_CODE_POINTS) return 'too-short';
  if (length > PASSWORD_MAX_CODE_POINTS) return 'too-long';
  return null;
}
