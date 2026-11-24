import { useState, useRef, useEffect } from 'react';
import { ChevronDown, Check } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslation } from 'react-i18next';
import { cn } from '@/lib/utils';

interface FilterOption {
  value: string;
  label: string;
  color?: string;
}

interface FilterDropdownProps {
  label: string;
  options: FilterOption[];
  selected: string[];
  onChange: (selected: string[]) => void;
  multiSelect?: boolean;
  placeholder?: string;
  searchable?: boolean;
}

export function FilterDropdown({
  label,
  options,
  selected,
  onChange,
  multiSelect = false,
  placeholder,
  searchable = false,
}: FilterDropdownProps) {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [query, setQuery] = useState('');
  const dropdownRef = useRef<HTMLDivElement>(null);

  const closeDropdown = () => {
    setIsOpen(false);
    setQuery('');
  };

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      const dropdown = dropdownRef.current;
      const clickedInside = event.target instanceof Node && dropdown?.contains(event.target) === true;
      if (dropdown !== null && !clickedInside) {
        setIsOpen(false);
        setQuery('');
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const normalizedQuery = query.trim().toLowerCase();
  const filteredOptions = searchable && normalizedQuery
    ? options.filter(
        (option) =>
          option.label.toLowerCase().includes(normalizedQuery) ||
          option.value.toLowerCase().includes(normalizedQuery),
      )
    : options;

  const handleSelect = (value: string) => {
    if (multiSelect) {
      if (selected.includes(value)) {
        onChange(selected.filter((v) => v !== value));
      } else {
        onChange([...selected, value]);
      }
    } else {
      onChange(selected.includes(value) ? [] : [value]);
      closeDropdown();
    }
  };

  return (
    <div className="relative" ref={dropdownRef}>
      <Button
        variant="outline"
        size="sm"
        onClick={() => (isOpen ? closeDropdown() : setIsOpen(true))}
        aria-haspopup="menu"
        aria-expanded={isOpen}
        className="h-8 px-3 justify-between w-[140px]"
      >
        <span className="flex items-center gap-1.5 truncate">
          {label}
          {selected.length > 0 && (
            <span className="text-xs text-primary">{selected.length}</span>
          )}
        </span>
        <ChevronDown className={`h-3.5 w-3.5 ml-1 shrink-0 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
      </Button>

      {isOpen && (
        <div role="menu" className="absolute top-full left-0 mt-1 w-56 bg-popover border border-border rounded-md shadow-lg z-50">
          {searchable && (
            <div className="p-1 border-b border-border">
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={placeholder ?? t('tickets.filterPlaceholder')}
                autoFocus
                className="w-full h-8 px-2 text-sm bg-background border border-border rounded-md outline-hidden focus:border-primary focus:ring-2 focus:ring-primary/20"
              />
            </div>
          )}
          <div className="max-h-60 overflow-y-auto p-1">
            {filteredOptions.length === 0 ? (
              <div className="px-2 py-4 text-center text-sm text-muted-foreground">
                {t('tickets.filter.noOptions')}
              </div>
            ) : (
              filteredOptions.map((option) => {
                const isSelected = selected.includes(option.value);
                return (
                  <button
                    key={option.value}
                    type="button"
                    role={multiSelect ? 'menuitemcheckbox' : 'menuitemradio'}
                    aria-checked={isSelected}
                    className="w-full flex items-center gap-2 px-2 py-1.5 text-sm rounded-md transition-colors hover:bg-muted"
                    onClick={() => handleSelect(option.value)}
                  >
                    <span
                      aria-hidden="true"
                      className={cn(
                        'h-4 w-4 shrink-0 flex items-center justify-center border',
                        multiSelect ? 'rounded-[4px]' : 'rounded-full',
                        isSelected ? 'border-primary' : 'border-muted-foreground/50',
                        isSelected && multiSelect && 'bg-primary text-primary-foreground',
                      )}
                    >
                      {isSelected && (multiSelect
                        ? <Check className="h-3.5 w-3.5" strokeWidth={3} />
                        : <span className="h-2 w-2 rounded-full bg-primary" />)}
                    </span>
                    {option.color && (
                      <span
                        className="w-3 h-3 rounded-full shrink-0"
                        style={{ backgroundColor: option.color }}
                      />
                    )}
                    <span className="truncate">{option.label}</span>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}
