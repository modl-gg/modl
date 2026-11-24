const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MAX_EMAIL_LENGTH = 254;

export function normalizeEmail(email: string | undefined | null): string | null {
  if (typeof email !== 'string') {
    return null;
  }

  const normalized = email.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

export function isValidEmail(email: string | undefined | null): boolean {
  const normalized = normalizeEmail(email);
  if (!normalized || normalized.length > MAX_EMAIL_LENGTH) {
    return false;
  }

  return EMAIL_REGEX.test(normalized);
}
