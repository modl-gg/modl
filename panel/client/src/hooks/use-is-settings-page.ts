import { useLocation } from 'wouter';
import { settingsPagePath, type SettingsPageId } from '@/lib/settings-access';

export function useIsSettingsPage(pageId: SettingsPageId): boolean {
  const [location] = useLocation();
  return location === settingsPagePath(pageId);
}
