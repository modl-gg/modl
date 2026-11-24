import type { GeneralSettingsChanges, GeneralSettingsSnapshot } from '@/hooks/data/settings';
import { DEFAULT_LANGUAGE_CODE } from './languages.ts';
import { normalizeHttpOrigin } from './http-origin.ts';

export interface ServerIdentity {
  serverDisplayName: string;
  defaultLanguage: string;
  homepageIconUrl: string;
  panelIconUrl: string;
}

export interface PanelIdentity extends ServerIdentity {
  publicUrl: string;
}

export const EMPTY_PANEL_IDENTITY: PanelIdentity = {
  serverDisplayName: '',
  defaultLanguage: DEFAULT_LANGUAGE_CODE,
  homepageIconUrl: '',
  panelIconUrl: '',
  publicUrl: '',
};

export function serverIdentityOf(snapshot: GeneralSettingsSnapshot): ServerIdentity {
  return {
    serverDisplayName: snapshot.serverDisplayName,
    defaultLanguage: snapshot.defaultLanguage || DEFAULT_LANGUAGE_CODE,
    homepageIconUrl: snapshot.homepageIconUrl,
    panelIconUrl: snapshot.panelIconUrl,
  };
}

export function panelIdentityOf(snapshot: GeneralSettingsSnapshot): PanelIdentity {
  return { ...serverIdentityOf(snapshot), publicUrl: snapshot.publicUrl };
}

export function serverIdentityChanges(next: ServerIdentity, base: GeneralSettingsSnapshot): GeneralSettingsChanges {
  const saved = serverIdentityOf(base);
  const changes: GeneralSettingsChanges = {};
  const name = next.serverDisplayName.trim();
  if (!base.serverNameManaged && name !== saved.serverDisplayName.trim()) changes.serverDisplayName = name;
  if (next.defaultLanguage !== saved.defaultLanguage) changes.defaultLanguage = next.defaultLanguage;
  if (next.homepageIconUrl !== saved.homepageIconUrl) changes.homepageIconUrl = next.homepageIconUrl;
  if (next.panelIconUrl !== saved.panelIconUrl) changes.panelIconUrl = next.panelIconUrl;
  return changes;
}

export function panelIdentityChanges(next: PanelIdentity, base: GeneralSettingsSnapshot): GeneralSettingsChanges {
  const changes = serverIdentityChanges(next, base);
  const publicUrl = normalizeHttpOrigin(next.publicUrl);
  if (!base.publicUrlManagedByConfig && publicUrl !== null && publicUrl !== base.publicUrl) changes.publicUrl = publicUrl;
  return changes;
}

export function isServerNameValid(identity: ServerIdentity, snapshot: GeneralSettingsSnapshot | undefined): boolean {
  return snapshot?.serverNameManaged === true || identity.serverDisplayName.trim() !== '';
}

export function isPublicUrlValid(identity: PanelIdentity, snapshot: GeneralSettingsSnapshot | undefined): boolean {
  if (snapshot?.publicUrlManagedByConfig === true || identity.publicUrl === snapshot?.publicUrl) return true;
  return normalizeHttpOrigin(identity.publicUrl) !== null;
}
