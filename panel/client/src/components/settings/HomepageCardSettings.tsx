import { useId, useState, type ComponentType, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Activity, AlertCircle, AlertTriangle, ArrowRight, Award, BarChart, Bell, Book, BookOpen, Bookmark, Calendar, Check, CheckCircle, ChevronRight, Clock, Cog, Compass, CreditCard, Crown, DollarSign, Download, ExternalLink, Eye, EyeOff, FileText, Filter, Flag, Gamepad2, Gift, Globe, GraduationCap, Headphones, Heart, HelpCircle, Home, Inbox, Info, Key, Library, Link as LinkIcon, Lock, Mail, Map, Megaphone, MessageCircle, MessageSquare, Mic, Minus, Navigation, Newspaper, Pause, Pencil, Phone, Play, Plus, Radio, Scale, ScrollText, Search, Send, Server, Settings, Share, Shield, ShieldAlert, ShieldCheck, Signal, Sliders, SquareArrowOutUpRight, Star, Tag, Target, ThumbsUp, Timer, Trash2, TrendingUp, Trophy, Unlock, Upload, User, UserCheck, UserPlus, UserX, Users, Video, Wifi, Wrench, X, XCircle, Zap } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { GroupRow, GroupRows } from '@/components/ui/group-list';
import { IconAction } from '@/components/ui/icon-action';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import {
  useCreateHomepageCard,
  useDeleteHomepageCard,
  useHomepageCards,
  useReorderHomepageCards,
  useUpdateHomepageCard,
  type HomepageCard,
  type HomepageCardActionType,
  type HomepageCardInput,
} from '@/hooks/data/homepage';
import { useKnowledgebaseCategories } from '@/hooks/data/knowledgebase';
import { useConfirmation } from '@/hooks/use-confirmation';
import { useFlipList } from '@/hooks/use-flip';
import { useSortable } from '@/hooks/use-sortable';
import { useToast } from '@/hooks/use-toast';
import { describeError } from '@/lib/http-error';
import { openExternalUrl, safeExternalHref } from '@/lib/utils';
import { SettingsPageAction, SettingsPageActionButton, useSettingsPageCanModify } from './layout/SettingsPageFrame';
import { SettingsLoading } from './layout/SettingsSections';
import { SettingsDndProvider } from './save/SettingsDndProvider';
import { useSavedOrder } from './save/use-saved-order';

const ICONS: Record<string, ComponentType<{ className?: string; style?: CSSProperties }>> = {
  Shield, UserPlus, MessageCircle, Mail, Phone, Scale,
  Users, User, UserCheck, UserX, Crown, Award,
  BookOpen, Book, FileText, ScrollText, Newspaper,
  Library, GraduationCap, HelpCircle, Info, AlertCircle,
  ExternalLink, Link: LinkIcon, ArrowRight, ChevronRight, Home,
  Search, Eye, Download, Upload, Share,
  MessageSquare, Send, Inbox, Bell, Megaphone,
  Radio, Headphones, Mic, Video, Calendar,
  Gamepad2, Zap, Server, Globe, Wifi, Signal,
  Activity, BarChart, TrendingUp, Target, Trophy,
  Settings, Wrench, Cog, Sliders, Filter,
  Lock, Unlock, Key, ShieldCheck, ShieldAlert,
  Plus, Minus, Check, X, AlertTriangle, CheckCircle,
  XCircle, Clock, Timer, Pause, Play,
  CreditCard, DollarSign, Gift, Star, Heart, ThumbsUp,
  Flag, Map, Compass, Navigation, Bookmark, Tag,
};

const AVAILABLE_ICONS = Object.keys(ICONS).sort();

const ITEM_TYPE_HOMEPAGE_CARD = 'homepage-card';

const DEFAULT_ICON_COLOR = '#3b82f6';

const EMPTY_CARD_FORM: HomepageCardInput = {
  title: '',
  description: '',
  icon: 'BookOpen',
  iconColor: DEFAULT_ICON_COLOR,
  actionType: 'url',
  actionUrl: '',
  actionButtonText: '',
  categoryId: '',
  backgroundColor: '',
  isEnabled: true
};

const NO_CARDS: readonly HomepageCard[] = [];

function isCardInputValid(card: HomepageCardInput): boolean {
  if (card.title.trim() === '' || card.description.trim() === '') return false;
  if (card.actionType === 'url') return safeExternalHref(card.actionUrl) !== undefined;
  return card.categoryId !== '';
}

const isHomepageCardActionType = (value: string): value is HomepageCardActionType =>
  value === 'url' || value === 'category_dropdown';

const IconPreview = ({ iconName }: { iconName: string }) => {
  const IconComponent = ICONS[iconName] ?? BookOpen;
  return <IconComponent className="h-5 w-5 text-muted-foreground" aria-hidden="true" />;
};

interface HomepageCardRowProps {
  card: HomepageCard;
  index: number;
  count: number;
  isSettling: () => boolean;
  onMove: (dragIndex: number, hoverIndex: number) => void;
  onEdit: (card: HomepageCard) => void;
  onDelete: (card: HomepageCard) => void;
}

function HomepageCardRow({ card, index, count, isSettling, onMove, onEdit, onDelete }: HomepageCardRowProps) {
  const { t } = useTranslation();
  const canModify = useSettingsPageCanModify();
  const sortable = useSortable<HTMLDivElement>({
    type: ITEM_TYPE_HOMEPAGE_CARD,
    id: card.id,
    label: card.title,
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
      leading={<IconPreview iconName={card.icon} />}
      title={card.title}
      meta={!card.isEnabled && <EyeOff className="h-4 w-4 shrink-0 text-muted-foreground" role="img" aria-label={t('settings.homepage.hidden')} />}
      description={card.description}
      detail={card.actionType === 'url'
        ? t('settings.homepage.cardUrlSummary', { url: card.actionUrl })
        : t('settings.homepage.cardCategorySummary', { name: card.category?.name ?? '' })}
      actions={canModify && (
        <>
          <IconAction icon={Pencil} label={t('settings.homepage.editCard')} onClick={() => { onEdit(card); }} />
          <IconAction icon={Trash2} label={t('settings.homepage.deleteCardTitle')} destructive onClick={() => { onDelete(card); }} />
        </>
      )}
    />
  );
}

export default function HomepageCardSettings() {
  const { toast } = useToast();
  const { t } = useTranslation();
  const [editingCard, setEditingCard] = useState<HomepageCard | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const confirmation = useConfirmation();
  const [formData, setFormData] = useState<HomepageCardInput>(EMPTY_CARD_FORM);
  const fieldId = useId();

  const { data: homepageCards, isLoading: isLoadingCards } = useHomepageCards();
  const { data: categories } = useKnowledgebaseCategories();

  const createCardMutation = useCreateHomepageCard();
  const updateCardMutation = useUpdateHomepageCard();
  const deleteCardMutation = useDeleteHomepageCard();
  const { mutateAsync: reorderCards } = useReorderHomepageCards();
  const cardOrder = useSavedOrder(homepageCards ?? NO_CARDS, async (ordered) => reorderCards(ordered.map((card) => card.id)));
  const cardList = useFlipList<HTMLDivElement>(cardOrder.items.map((card) => card.id));

  const toastFailure = (error: unknown, fallbackKey: string) => {
    toast({ title: t('toast.error'), description: describeError(error, t(fallbackKey)), variant: 'destructive' });
  };

  const formValid = isCardInputValid(formData);

  const closeEditor = () => {
    setIsCreating(false);
    setEditingCard(null);
    setFormData(EMPTY_CARD_FORM);
  };

  const saveCard = () => {
    if (!formValid) return;
    const callbacks = (failureKey: string) => ({
      onSuccess: closeEditor,
      onError: (error: unknown) => { toastFailure(error, failureKey); },
    });
    if (editingCard) {
      updateCardMutation.mutate({ id: editingCard.id, card: formData }, callbacks('settings.homepage.cardUpdateFailed'));
    } else {
      createCardMutation.mutate(formData, callbacks('settings.homepage.cardCreateFailed'));
    }
  };

  const handleEditCard = (card: HomepageCard) => {
    setEditingCard(card);
    setFormData({
      title: card.title,
      description: card.description,
      icon: card.icon,
      iconColor: card.iconColor || DEFAULT_ICON_COLOR,
      actionType: card.actionType,
      actionUrl: card.actionUrl,
      actionButtonText: card.actionButtonText,
      categoryId: card.categoryId,
      backgroundColor: card.backgroundColor,
      isEnabled: card.isEnabled
    });
    setIsCreating(true);
  };

  const deleteCard = async (card: HomepageCard) => {
    const confirmed = await confirmation.confirm({
      title: t('settings.homepage.deleteCardTitle'),
      description: t('settings.homepage.deleteCardConfirm'),
      action: t('common.delete'),
      destructive: true,
    });
    if (!confirmed) return;
    deleteCardMutation.mutate(card.id, {
      onError: (error) => { toastFailure(error, 'settings.homepage.cardDeleteFailed'); },
    });
  };

  if (isLoadingCards) return <SettingsLoading />;

  return (
    <SettingsDndProvider>
      <SettingsPageAction requiresModify={false}>
        <SettingsPageActionButton icon={SquareArrowOutUpRight} label={t('settings.homepage.openHomepage')} onClick={() => { openExternalUrl('/'); }} />
      </SettingsPageAction>
      <SettingsPageAction>
        <SettingsPageActionButton icon={Plus} label={t('settings.homepage.addNewCard')} onClick={() => { setIsCreating(true); }} />
      </SettingsPageAction>
      {cardOrder.items.length === 0 ? (
        <p className="py-8 text-center text-sm text-muted-foreground">{t('settings.homepage.noCards')}</p>
      ) : (
        <GroupRows ref={cardList.ref}>
          {cardOrder.items.map((card, index) => (
            <HomepageCardRow
              key={card.id}
              card={card}
              index={index}
              count={cardOrder.items.length}
              isSettling={cardList.isSettling}
              onMove={cardOrder.move}
              onEdit={handleEditCard}
              onDelete={(deleted) => { void deleteCard(deleted); }}
            />
          ))}
        </GroupRows>
      )}

      <Dialog open={isCreating} onOpenChange={(open) => {
        if (!open) closeEditor();
      }}>
        <DialogContent className="max-w-2xl" aria-describedby={undefined} onInteractOutside={(event) => { event.preventDefault(); }}>
          <DialogHeader>
            <DialogTitle>{editingCard ? t('settings.homepage.editCard') : t('settings.homepage.addNewCard')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor={`${fieldId}-title`}>{t('settings.homepage.cardTitle')}</Label>
                <Input
                  id={`${fieldId}-title`}
                  placeholder={t('settings.homepage.cardTitlePlaceholder')}
                  value={formData.title}
                  onChange={(e) => setFormData({ ...formData, title: e.target.value })}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={`${fieldId}-icon`}>{t('settings.homepage.icon')}</Label>
                <Select value={formData.icon} onValueChange={(value) => setFormData({ ...formData, icon: value })}>
                  <SelectTrigger id={`${fieldId}-icon`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent className="max-h-48">
                    {AVAILABLE_ICONS.map(iconName => (
                      <SelectItem key={iconName} value={iconName}>
                        <div className="flex items-center gap-2">
                          <IconPreview iconName={iconName} />
                          {iconName}
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-description`}>{t('settings.homepage.cardDescription')}</Label>
              <Textarea
                id={`${fieldId}-description`}
                placeholder={t('settings.homepage.cardDescriptionPlaceholder')}
                value={formData.description}
                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor={`${fieldId}-actionType`}>{t('settings.homepage.actionType')}</Label>
              <Select 
                value={formData.actionType} 
                onValueChange={(value) => {
                  if (isHomepageCardActionType(value)) setFormData({ ...formData, actionType: value });
                }}
              >
                <SelectTrigger id={`${fieldId}-actionType`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="url">{t('settings.homepage.urlAction')}</SelectItem>
                  <SelectItem value="category_dropdown">{t('settings.homepage.categoryDropdown')}</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {formData.actionType === 'url' ? (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label htmlFor={`${fieldId}-actionUrl`}>{t('settings.homepage.url')}</Label>
                  <Input
                    id={`${fieldId}-actionUrl`}
                    placeholder={t('settings.homepage.urlPlaceholder')}
                    value={formData.actionUrl}
                    onChange={(e) => setFormData({ ...formData, actionUrl: e.target.value })}
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor={`${fieldId}-actionButtonText`}>{t('settings.homepage.buttonText')}</Label>
                  <Input
                    id={`${fieldId}-actionButtonText`}
                    placeholder={t('settings.homepage.buttonTextPlaceholder')}
                    value={formData.actionButtonText}
                    onChange={(e) => setFormData({ ...formData, actionButtonText: e.target.value })}
                  />
                </div>
              </div>
            ) : (
              <div className="space-y-2">
                <Label htmlFor={`${fieldId}-categoryId`}>{t('settings.homepage.category')}</Label>
                <Select
                  value={formData.categoryId}
                  onValueChange={(value) => setFormData({ ...formData, categoryId: value })}
                >
                  <SelectTrigger id={`${fieldId}-categoryId`}>
                    <SelectValue placeholder={t('settings.homepage.selectCategory')} />
                  </SelectTrigger>
                  <SelectContent>
                    {categories?.map(category => (
                      <SelectItem key={category.id} value={category.id}>
                        {category.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}

            <div className="flex items-center space-x-2">
              <Switch
                id={`${fieldId}-isEnabled`}
                checked={formData.isEnabled}
                onCheckedChange={(checked) => setFormData({ ...formData, isEnabled: checked })}
              />
              <Label htmlFor={`${fieldId}-isEnabled`}>{t('common.enabled')}</Label>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={closeEditor}>{t('common.cancel')}</Button>
            <Button type="button" onClick={saveCard} disabled={!formValid || createCardMutation.isPending || updateCardMutation.isPending}>
              {editingCard ? t('settings.homepage.updateCard') : t('settings.homepage.createCard')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {confirmation.dialog}
    </SettingsDndProvider>
  );
}
