import i18n, { type BackendModule, type ResourceKey } from 'i18next';
import { initReactI18next } from 'react-i18next';

import { isSupportedLanguage, type SupportedLanguageCode } from '@/lib/languages';
import en from '@/locales/en.json';

type TranslationLoader = () => Promise<ResourceKey>;

const translationLoaders: Record<Exclude<SupportedLanguageCode, 'en'>, TranslationLoader> = {
  de: async () => (await import('@/locales/de.json')).default,
  es: async () => (await import('@/locales/es.json')).default,
  fr: async () => (await import('@/locales/fr.json')).default,
  hi: async () => (await import('@/locales/hi.json')).default,
  it: async () => (await import('@/locales/it.json')).default,
  ja: async () => (await import('@/locales/ja.json')).default,
  nl: async () => (await import('@/locales/nl.json')).default,
  pt: async () => (await import('@/locales/pt.json')).default,
  ru: async () => (await import('@/locales/ru.json')).default,
  zh: async () => (await import('@/locales/zh.json')).default,
};

async function loadTranslation(language: SupportedLanguageCode): Promise<ResourceKey> {
  return language === 'en' ? en : await translationLoaders[language]();
}

function asError(reason: unknown): Error {
  return reason instanceof Error ? reason : new Error(String(reason));
}

const lazyTranslationBackend: BackendModule = {
  type: 'backend',
  callbackApi: true,
  init: () => undefined,
  read: (language, _namespace, callback) => {
    if (!isSupportedLanguage(language)) {
      callback(new Error(`Unsupported language: ${language}`), false);
      return;
    }
    loadTranslation(language).then(
      (translation) => callback(null, translation),
      (reason: unknown) => callback(asError(reason), false),
    );
  },
};

void i18n
  .use(lazyTranslationBackend)
  .use(initReactI18next)
  .init({
    resources: { en: { translation: en } },
    partialBundledLanguages: true,
    initAsync: false,
    lng: 'en',
    fallbackLng: 'en',
    interpolation: {
      escapeValue: false,
    },
  });

i18n.on('languageChanged', (language) => {
  document.documentElement.lang = language;
});

export default i18n;
