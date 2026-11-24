import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import eslintReact from '@eslint-react/eslint-plugin';
import tanstackQuery from '@tanstack/eslint-plugin-query';
import i18next from 'eslint-plugin-i18next';
import globals from 'globals';
import { defineConfig, globalIgnores } from 'eslint/config';
import { strictLinterOptions, strictTypeScriptConfigs } from '../eslint.strict.mjs';

const typeScriptFiles = ['**/*.ts', '**/*.tsx'];
const componentFiles = ['client/src/**/*.tsx'];
const userFacingAttributes = 'placeholder|title|alt|aria-label|aria-description|label|description|tooltip';
const translateMessage = 'User-facing text goes through a locale key in client/src/locales (t()), never a literal string.';
const userFacingAttribute = `JSXAttribute[name.name=/^(${userFacingAttributes})$/]`;
const toastText = 'CallExpression[callee.name="toast"] Property[key.name=/^(title|description)$/]';
const literalForms = [
  '> Literal[value=/[A-Za-z]/]',
  '> TemplateLiteral > TemplateElement[value.raw=/[A-Za-z]/]',
  '> ConditionalExpression > Literal[value=/[A-Za-z]/]',
  '> LogicalExpression > Literal[value=/[A-Za-z]/]',
];
const untranslatedText = [
  `${userFacingAttribute} > Literal[value=/[A-Za-z]/]`,
  ...literalForms.map((form) => `${userFacingAttribute} > JSXExpressionContainer ${form}`),
  ...literalForms.map((form) => `${toastText} ${form}`),
].map((selector) => ({ selector, message: translateMessage }));
const asyncComponentMessage = 'React client components cannot be async; return JSX (wrap a ReactNode in a fragment) and load data in a hook.';
const asyncComponents = [
  'FunctionDeclaration[async=true][id.name=/^[A-Z]/]',
  'VariableDeclarator[id.name=/^[A-Z]/] > ArrowFunctionExpression[async=true]',
  'VariableDeclarator[id.name=/^[A-Z]/] > FunctionExpression[async=true]',
].map((selector) => ({ selector, message: asyncComponentMessage }));

function asErrors(rules) {
  return Object.fromEntries(Object.entries(rules).map(([name, level]) => [name, level === 'off' ? 'off' : 'error']));
}

export default defineConfig(
  globalIgnores(['dist/**', 'scripts/atlas/node_modules/**']),
  { linterOptions: strictLinterOptions },
  strictTypeScriptConfigs({
    js: eslint,
    tseslint,
    files: typeScriptFiles,
    tsconfigRootDir: import.meta.dirname,
    project: ['./tsconfig.json', './tsconfig.node.json'],
    restrictedSyntax: [...untranslatedText, ...asyncComponents],
  }),
  {
    files: ['client/src/lib/copy-text.ts'],
    rules: {
      '@typescript-eslint/no-deprecated': ['error', { allow: [{ from: 'lib', name: 'execCommand' }] }],
    },
  },
  {
    files: typeScriptFiles,
    plugins: { 'react-hooks': reactHooks, '@tanstack/query': tanstackQuery },
    rules: {
      ...asErrors(reactHooks.configs['recommended-latest'].rules),
      ...asErrors(tanstackQuery.configs['flat/recommended'][0].rules),
    },
  },
  {
    files: typeScriptFiles,
    ...eslintReact.configs['recommended-type-checked'],
    rules: asErrors({
      ...eslintReact.configs['recommended-type-checked'].rules,
      ...eslintReact.configs['disable-conflict-eslint-plugin-react-hooks'].rules,
    }),
  },
  {
    files: componentFiles,
    plugins: { i18next },
    rules: {
      'i18next/no-literal-string': ['error', { mode: 'jsx-text-only', message: translateMessage }],
    },
  },
  {
    files: ['**/*.js', '**/*.mjs'],
    extends: [eslint.configs.recommended],
    languageOptions: { globals: globals.node },
  },
);
