import { z } from 'zod';
import i18n from '@/lib/i18n';

const rateLimitResponseSchema = z.object({
  timeRemaining: z.string().optional().catch(undefined),
  retryAfterSeconds: z.number().int().positive().optional().catch(undefined),
});

type RateLimitResponse = z.infer<typeof rateLimitResponseSchema>;

const NOTICE_INTERVAL_MS = 5000;
let lastNoticeAt = 0;

function formatSeconds(seconds: number): string {
  return new Intl.NumberFormat(i18n.language, { style: 'unit', unit: 'second', unitDisplay: 'long' }).format(seconds);
}

function waitTime(body: RateLimitResponse): string | null {
  if (body.timeRemaining) return body.timeRemaining;
  if (body.retryAfterSeconds !== undefined) return formatSeconds(body.retryAfterSeconds);
  return null;
}

export async function handleRateLimitResponse(response: Response): Promise<void> {
  const now = Date.now();
  if (now - lastNoticeAt < NOTICE_INTERVAL_MS) return;
  lastNoticeAt = now;
  const { toast } = await import('@/hooks/use-toast');
  const body = rateLimitResponseSchema.safeParse(await response.json().catch(() => null));
  const time = body.success ? waitTime(body.data) : null;
  toast({
    title: i18n.t('toast.rateLimitExceeded'),
    description: time === null ? i18n.t('toast.rateLimitExceededDesc') : i18n.t('toast.rateLimitWait', { time }),
    variant: 'destructive',
  });
}
