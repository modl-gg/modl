import { z } from 'zod';
import { isValidSetupCode } from '@/lib/setup-code';

export function trimmedEmail(error: string) {
  return z.string().trim().pipe(z.email({ error }));
}

export function signInEmail(error: string) {
  return z.string().trim().pipe(z.email({ pattern: z.regexes.html5Email, error }));
}

export function setupCode(error: string) {
  return z.string().refine(isValidSetupCode, { error });
}
