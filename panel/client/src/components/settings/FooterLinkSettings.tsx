import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FooterLinkIcon } from '@/components/ui/footer-link-icon';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
  FOOTER_LINK_KINDS,
  MAX_FOOTER_LINK_LABEL_LENGTH,
  MAX_FOOTER_LINK_URL_LENGTH,
  MAX_FOOTER_LINKS,
  type FooterLink,
  type FooterLinkKind,
} from '@/hooks/data/homepage-settings';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable } from '@/hooks/use-sortable';
import { movedItem } from '@/lib/move-item';
import { absoluteWebHref } from '@/lib/utils';
import { SettingsSection } from './layout/SettingsSections';

export interface EditableFooterLink extends FooterLink {
  id: string;
}

const ITEM_TYPE_FOOTER_LINK = 'footer-link';
const SAVED_LINK_PREFIX = 'saved-';
const EMPTY_LINK: FooterLink = { label: '', url: '', kind: 'discord' };
const NEW_LINK_PREFIX = 'new-';

const KIND_LABEL_KEYS: Readonly<Record<FooterLinkKind, string>> = {
  discord: 'settings.homepage.linkKinds.discord',
  store: 'settings.homepage.linkKinds.store',
  website: 'settings.homepage.linkKinds.website',
  vote: 'settings.homepage.linkKinds.vote',
  other: 'settings.homepage.linkKinds.other',
};

export function editableFooterLinks(links: readonly FooterLink[]): EditableFooterLink[] {
  return links.map((link, index) => ({ id: `${SAVED_LINK_PREFIX}${String(index)}`, ...link }));
}

export function savedFooterLinks(links: readonly EditableFooterLink[]): FooterLink[] {
  return links.map(({ label, url, kind }) => ({ label, url, kind }));
}

export function isFooterLinkValid(link: FooterLink): boolean {
  const label = link.label.trim();
  return label !== '' && label.length <= MAX_FOOTER_LINK_LABEL_LENGTH && link.url.trim().length <= MAX_FOOTER_LINK_URL_LENGTH && absoluteWebHref(link.url) !== undefined;
}

function isFooterLinkKind(value: string): value is FooterLinkKind {
  return FOOTER_LINK_KINDS.some((kind) => kind === value);
}

interface FooterLinkRowProps {
  link: EditableFooterLink;
  index: number;
  count: number;
  canModify: boolean;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (link: EditableFooterLink) => void;
  onDelete: (link: EditableFooterLink) => void;
}

function FooterLinkRow({ link, index, count, canModify, isSettling, onMove, onEdit, onDelete }: FooterLinkRowProps) {
  const { t } = useTranslation();
  const sortable = useSortable<HTMLDivElement>({
    type: ITEM_TYPE_FOOTER_LINK,
    id: link.id,
    label: link.label,
    index,
    count,
    disabled: !canModify,
    isSettling,
    onMove,
  });
  return (
    <GroupRow
      ref={sortable.ref}
      handle={sortable.handle}
      dragging={sortable.isDragging}
      leading={<FooterLinkIcon kind={link.kind} className="text-muted-foreground" />}
      title={link.label}
      detail={link.url}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.homepage.editLink')} onClick={() => { onEdit(link); }} />
          <IconAction icon={Trash2} label={t('settings.homepage.deleteLinkTitle')} destructive onClick={() => { onDelete(link); }} />
        </>
      )}
    />
  );
}

interface FooterLinkDialogProps {
  editing: EditableFooterLink | null;
  onClose: () => void;
  onSubmit: (link: FooterLink) => void;
}

function FooterLinkDialog({ editing, onClose, onSubmit }: FooterLinkDialogProps) {
  const { t } = useTranslation();
  const fieldId = useId();
  const [form, setForm] = useState<FooterLink>(editing ?? EMPTY_LINK);
  const valid = isFooterLinkValid(form);

  return (
    <Dialog open onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent className="max-w-lg" aria-describedby={undefined} onInteractOutside={(event) => { event.preventDefault(); }}>
        <DialogHeader>
          <DialogTitle>{editing === null ? t('settings.homepage.addLink') : t('settings.homepage.editLink')}</DialogTitle>
        </DialogHeader>
        <form
          id={`${fieldId}-form`}
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (valid) onSubmit({ label: form.label.trim(), url: form.url.trim(), kind: form.kind });
          }}
        >
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-kind`}>{t('settings.homepage.linkKind')}</Label>
            <Select value={form.kind} onValueChange={(value) => { if (isFooterLinkKind(value)) setForm({ ...form, kind: value }); }}>
              <SelectTrigger id={`${fieldId}-kind`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {FOOTER_LINK_KINDS.map((kind) => (
                  <SelectItem key={kind} value={kind}>
                    <span className="flex items-center gap-2">
                      <FooterLinkIcon kind={kind} className="text-muted-foreground" />
                      {t(KIND_LABEL_KEYS[kind])}
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-label`}>{t('settings.homepage.linkLabel')}</Label>
            <Input
              id={`${fieldId}-label`}
              value={form.label}
              maxLength={MAX_FOOTER_LINK_LABEL_LENGTH}
              onChange={(event) => { setForm({ ...form, label: event.target.value }); }}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor={`${fieldId}-url`}>{t('settings.homepage.linkUrl')}</Label>
            <Input
              id={`${fieldId}-url`}
              type="url"
              inputMode="url"
              spellCheck={false}
              placeholder={t('settings.homepage.linkUrlPlaceholder')}
              value={form.url}
              maxLength={MAX_FOOTER_LINK_URL_LENGTH}
              onChange={(event) => { setForm({ ...form, url: event.target.value }); }}
            />
          </div>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form={`${fieldId}-form`} disabled={!valid}>
            {editing === null ? t('common.add') : t('common.update')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface FooterLinkSettingsProps {
  links: EditableFooterLink[];
  canModify: boolean;
  onChange: (update: (links: EditableFooterLink[]) => EditableFooterLink[]) => void;
}

type EditorState = { mode: 'closed' } | { mode: 'create' } | { mode: 'edit'; link: EditableFooterLink };

export function FooterLinkSettings({ links, canModify, onChange }: FooterLinkSettingsProps) {
  const { t } = useTranslation();
  const confirmation = useConfirmation();
  const [editor, setEditor] = useState<EditorState>({ mode: 'closed' });
  const nextLinkIdRef = useRef(0);
  const linkList = useFlipList<HTMLDivElement>(links.map((link) => link.id));

  const closeEditor = () => { setEditor({ mode: 'closed' }); };

  const submit = (link: FooterLink) => {
    if (editor.mode === 'edit') {
      const { id } = editor.link;
      onChange((current) => current.map((candidate) => (candidate.id === id ? { id, ...link } : candidate)));
    } else {
      nextLinkIdRef.current += 1;
      const id = `${NEW_LINK_PREFIX}${String(nextLinkIdRef.current)}`;
      onChange((current) => (current.length >= MAX_FOOTER_LINKS ? current : [...current, { id, ...link }]));
    }
    closeEditor();
  };

  const remove = async (link: EditableFooterLink) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.homepage.deleteLinkTitle'),
      description: t('settings.homepage.deleteLinkConfirm', { label: link.label }),
      action: t('common.delete'),
      destructive: true,
    });
    if (confirmed) onChange((current) => current.filter((candidate) => candidate.id !== link.id));
  };

  const move = (dragIndex: number, hoverIndex: number) => {
    onChange((current) => movedItem(current, dragIndex, hoverIndex));
  };

  return (
    <SettingsSection
      title={t('settings.homepage.footerLinks')}
      action={canModify && (
        <IconAction
          icon={Plus}
          label={t('settings.homepage.addLink')}
          disabled={links.length >= MAX_FOOTER_LINKS}
          onClick={() => { setEditor({ mode: 'create' }); }}
        />
      )}
    >
      {links.length === 0 ? (
        <p className="py-4 text-center text-sm text-muted-foreground">{t('settings.homepage.noLinks')}</p>
      ) : (
        <GroupRows ref={linkList.ref}>
          {links.map((link, index) => (
            <FooterLinkRow
              key={link.id}
              link={link}
              index={index}
              count={links.length}
              canModify={canModify}
              isSettling={linkList.isSettling}
              onMove={move}
              onEdit={(edited) => { setEditor({ mode: 'edit', link: edited }); }}
              onDelete={(deleted) => { void remove(deleted); }}
            />
          ))}
        </GroupRows>
      )}
      {editor.mode !== 'closed' && (
        <FooterLinkDialog
          editing={editor.mode === 'edit' ? editor.link : null}
          onClose={closeEditor}
          onSubmit={submit}
        />
      )}
      {confirmation.dialog}
    </SettingsSection>
  );
}
