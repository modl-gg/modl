import { useId, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, Loader2, RefreshCw } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Notice } from '@/components/ui/notice';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useAiModels, type AiModelList, type AiModelOption } from '@/hooks/data/ai';
import { cn } from '@/lib/utils';
import { canListModels, draftRequest, isTypedModelNew, visibleModels, type AiDraft } from '@/lib/ai-connection';
import { formatDateOnly } from '@/utils/date-utils';
import { aiErrorMessageKey, FAILURE_MESSAGES } from './ai-messages';

const MODEL_MAX_LENGTH = 200;
const RENDERED_MODELS = 200;

interface LoadedModels {
  source: string;
  list: AiModelList;
}

interface LoadError {
  source: string;
  messageKey: string;
}

function ModelBadges({ model }: { model: AiModelOption }) {
  const { t } = useTranslation();

  return (
    <>
      {model.deprecated && <Badge variant="destructive">{t('settings.ai.badge.deprecated')}</Badge>}
      {model.retiresAt !== null && (
        <Badge variant="secondary">{t('settings.ai.badge.retiring', { date: formatDateOnly(model.retiresAt) })}</Badge>
      )}
      {model.structuredOutputs === true && <Badge variant="secondary">{t('settings.ai.badge.structured')}</Badge>}
    </>
  );
}

interface AiModelPickerProps {
  draft: AiDraft;
  disabled: boolean;
  onChange: (model: string) => void;
}

export function AiModelPicker({ draft, disabled, onChange }: AiModelPickerProps) {
  const { t } = useTranslation();
  const id = useId();
  const listModels = useAiModels();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [loaded, setLoaded] = useState<LoadedModels | null>(null);
  const [loadError, setLoadError] = useState<LoadError | null>(null);

  const request = draftRequest({ ...draft, model: '' });
  const source = JSON.stringify([request, draft.storedKeyApplies]);
  const list = loaded?.source === source ? loaded.list : null;
  const errorKey = loadError?.source === source ? loadError.messageKey : null;
  const models = list?.models ?? [];
  const matches = visibleModels(models, query, showAll).slice(0, RENDERED_MODELS);
  const typed = query.trim();
  const choices = isTypedModelNew(models, query) ? [...matches.map((model) => model.id), typed] : matches.map((model) => model.id);
  const active = Math.min(activeIndex, Math.max(0, choices.length - 1));
  const optionId = (index: number) => `${id}-option-${index.toString()}`;

  const load = async (refresh: boolean) => {
    setLoadError(null);
    try {
      setLoaded({ source, list: await listModels.mutateAsync({ draft: request, refresh }) });
    } catch (error) {
      setLoadError({ source, messageKey: aiErrorMessageKey(error, 'settings.ai.failure.providerError') });
    }
  };

  const changeOpen = (next: boolean) => {
    setOpen(next);
    setQuery('');
    setActiveIndex(0);
    if (next && list === null && !listModels.isPending && canListModels(draft)) {
      void load(false);
    }
  };

  const choose = (model: string) => {
    onChange(model);
    changeOpen(false);
  };

  const navigate = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      const next = Math.max(0, Math.min(active + step, choices.length - 1));
      setActiveIndex(next);
      document.getElementById(optionId(next))?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const chosen = choices[active];
      if (chosen !== undefined) choose(chosen);
    }
  };

  return (
    <Field label={t('settings.ai.model')}>
      {(control) => (
        <Popover open={open} onOpenChange={changeOpen}>
          <PopoverTrigger asChild>
            <Button {...control} type="button" variant="outline" disabled={disabled || !canListModels(draft)} className="w-full justify-between font-normal">
              <span className={cn('truncate', draft.model === '' && 'text-muted-foreground')}>
                {draft.model === '' ? t('settings.ai.modelPlaceholder') : draft.model}
              </span>
              <ChevronDown className="h-4 w-4 shrink-0 opacity-50" aria-hidden="true" />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" className="w-(--radix-popover-trigger-width) max-w-[90vw] space-y-2 p-2">
            <div className="flex gap-2">
              <Input
                role="combobox"
                aria-expanded={open}
                aria-controls={`${id}-list`}
                aria-activedescendant={choices.length > 0 ? optionId(active) : undefined}
                aria-label={t('settings.ai.modelSearch')}
                placeholder={t('settings.ai.modelSearch')}
                value={query}
                maxLength={MODEL_MAX_LENGTH}
                autoComplete="off"
                spellCheck={false}
                onKeyDown={navigate}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setActiveIndex(0);
                }}
              />
              <Button
                type="button"
                variant="outline"
                size="icon"
                aria-label={t('settings.ai.modelRefresh')}
                disabled={listModels.isPending || !canListModels(draft)}
                onClick={() => {
                  void load(true);
                }}
              >
                {listModels.isPending
                  ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  : <RefreshCw className="h-4 w-4" aria-hidden="true" />}
              </Button>
            </div>

            {errorKey !== null && <Notice variant="error" announce>{t(errorKey)}</Notice>}
            {list?.failure && <Notice variant="warning" announce>{t(FAILURE_MESSAGES[list.failure])}</Notice>}
            {list?.stale === true && <p className="text-xs text-muted-foreground">{t('settings.ai.modelsStale')}</p>}

            <div id={`${id}-list`} role="listbox" aria-label={t('settings.ai.model')} className="max-h-64 overflow-y-auto">
              {choices.map((choice, index) => {
                const model = matches[index];
                return (
                  <button
                    key={choice}
                    id={optionId(index)}
                    type="button"
                    role="option"
                    tabIndex={-1}
                    aria-selected={choice === draft.model}
                    className={cn('flex w-full flex-wrap items-center gap-1 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-muted',
                      index === active && 'bg-muted')}
                    onClick={() => {
                      choose(choice);
                    }}
                  >
                    <span className="break-all">{model ? choice : t('settings.ai.modelManual', { model: choice })}</span>
                    {model?.displayName && model.displayName !== choice && <span className="text-xs text-muted-foreground">{model.displayName}</span>}
                    {model && <ModelBadges model={model} />}
                  </button>
                );
              })}
            </div>
            {choices.length === 0 && !listModels.isPending && <p className="px-2 text-xs text-muted-foreground">{t('settings.ai.modelNone')}</p>}

            {models.some((model) => !model.chatCapable) && (
              <div className="flex items-center gap-2 px-2 text-xs">
                <Checkbox
                  id={`${id}-all`}
                  checked={showAll}
                  onCheckedChange={(checked) => {
                    setShowAll(checked === true);
                  }}
                />
                <Label htmlFor={`${id}-all`} className="text-xs font-normal">{t('settings.ai.modelShowAll')}</Label>
              </div>
            )}
          </PopoverContent>
        </Popover>
      )}
    </Field>
  );
}
