import { useState } from 'react';
import { useLocation } from 'wouter';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Notice } from '@/components/ui/notice';
import { useTranslation } from 'react-i18next';
import { useSecondsRemaining } from '@/hooks/use-seconds-remaining';
import { clearRateLimitInfo, rateLimitDeadline, readRateLimitInfo, takePreRateLimitPath } from '@/lib/rate-limit-info';
import { PANEL_HOME_PATH, safeReturnPath } from '@/lib/return-path';

function formatTime(seconds: number): string {
  if (seconds <= 0) return '0s';

  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = seconds % 60;

  if (minutes > 0) {
    return `${minutes}m ${remainingSeconds}s`;
  }
  return `${remainingSeconds}s`;
}

export default function RateLimitPage() {
  const { t } = useTranslation();
  const [, navigate] = useLocation();
  const [rateLimitInfo] = useState(readRateLimitInfo);
  const [deadline] = useState(() => rateLimitDeadline(rateLimitInfo, Date.now()));
  const timeLeft = useSecondsRemaining(deadline);

  const handleRetry = () => {
    navigate(safeReturnPath(takePreRateLimitPath()) ?? PANEL_HOME_PATH);
  };

  const handleDashboard = () => {
    clearRateLimitInfo();
    navigate(PANEL_HOME_PATH);
  };

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4">
      <Card className="w-full max-w-md mx-auto shadow-card">
        <CardHeader>
          <CardTitle>{t('pages.rateLimit.title')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {rateLimitInfo.message !== undefined && (
            <p className="text-sm">{rateLimitInfo.message}</p>
          )}
          {rateLimitInfo.securityNote !== undefined && (
            <Notice variant="info">{rateLimitInfo.securityNote}</Notice>
          )}
          <div className="space-y-2">
            <Button onClick={handleRetry} className="w-full" disabled={timeLeft > 0}>
              {timeLeft > 0 ? t('pages.rateLimit.wait', { time: formatTime(timeLeft) }) : t('common.tryAgain')}
            </Button>
            <Button onClick={handleDashboard} variant="outline" className="w-full">
              {t('pages.rateLimit.returnToDashboard')}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
