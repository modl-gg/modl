import { Button } from "@/components/ui/button";
import { useLocation } from "wouter";
import { useEffect } from "react";
import { useTranslation } from 'react-i18next';

export default function NotFound() {
  const { t } = useTranslation();
  const [, setLocation] = useLocation();

  useEffect(() => {
    document.title = t('pages.notFound.title');
  }, [t]);

  const handleReturnHome = () => {
    const currentPath = window.location.pathname;
    if (currentPath.startsWith('/panel')) {
      setLocation('/panel');
    } else {
      setLocation('/');
    }
  };

  return (
    <div className="min-h-screen w-full flex items-center justify-center bg-background p-4">
      <div className="flex flex-col items-center text-center gap-2">
        <h1 className="text-xl font-semibold">{t('pages.notFound.title')}</h1>
        <Button onClick={handleReturnHome} variant="outline" className="mt-2">
          {t('pages.notFound.returnHome')}
        </Button>
      </div>
    </div>
  );
}
