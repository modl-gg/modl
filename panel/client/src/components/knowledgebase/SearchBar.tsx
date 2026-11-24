import React from 'react';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';

interface SearchBarProps {
  searchTerm: string;
  onSearchTermChange: (term: string) => void;
  placeholder?: string;
}

const SearchBar: React.FC<SearchBarProps> = ({
  searchTerm,
  onSearchTermChange,
  placeholder,
}) => {
  const { t } = useTranslation();
  return (
    <Input
      data-modl="search"
      type="search"
      aria-label={placeholder ?? t('home.searchPlaceholder')}
      placeholder={placeholder ?? t('home.searchPlaceholder')}
      value={searchTerm}
      onChange={(e) => onSearchTermChange(e.target.value)}
    />
  );
};

export default SearchBar;
