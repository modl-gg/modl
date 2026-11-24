export const strictLinterOptions = {
  noInlineConfig: true,
  reportUnusedDisableDirectives: 'error',
  reportUnusedInlineConfigs: 'error',
};

const erasableSyntaxSelectors = [
  { selector: 'TSEnumDeclaration', message: 'Use a union of string literals or an as-const object instead of an enum.' },
  { selector: 'TSModuleDeclaration[kind="namespace"]', message: 'Use ES modules instead of namespaces.' },
  { selector: 'TSParameterProperty', message: 'Declare the field explicitly instead of a parameter property.' },
];

export const strictTypeScriptRules = {
  eqeqeq: ['error', 'always'],
  radix: 'error',
  'no-console': 'error',
  'no-shadow': 'off',
  'prefer-const': ['error', { destructuring: 'all' }],
  '@typescript-eslint/ban-ts-comment': [
    'error',
    { 'ts-expect-error': true, 'ts-ignore': true, 'ts-nocheck': true, 'ts-check': false },
  ],
  '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports', fixStyle: 'separate-type-imports' }],
  '@typescript-eslint/no-shadow': 'error',
  '@typescript-eslint/no-unsafe-type-assertion': 'error',
  '@typescript-eslint/prefer-readonly': 'error',
  '@typescript-eslint/promise-function-async': 'error',
  '@typescript-eslint/switch-exhaustiveness-check': [
    'error',
    { allowDefaultCaseForExhaustiveSwitch: false, requireDefaultForNonUnion: true, considerDefaultExhaustiveForUnions: false },
  ],
  '@typescript-eslint/restrict-template-expressions': [
    'error',
    { allowNumber: true, allowBoolean: false, allowAny: false, allowNullish: false, allowRegExp: false, allowNever: false },
  ],
  '@typescript-eslint/no-confusing-void-expression': ['error', { ignoreArrowShorthand: true }],
  '@typescript-eslint/prefer-nullish-coalescing': ['error', { ignorePrimitives: { string: true } }],
  '@typescript-eslint/strict-boolean-expressions': [
    'error',
    {
      allowString: true,
      allowNumber: true,
      allowNullableObject: true,
      allowNullableBoolean: true,
      allowNullableString: true,
      allowNullableNumber: false,
      allowNullableEnum: false,
      allowAny: false,
    },
  ],
  '@typescript-eslint/no-unused-vars': ['error', { args: 'all', argsIgnorePattern: '^_', caughtErrors: 'all', caughtErrorsIgnorePattern: '^_' }],
};

export function strictTypeScriptConfigs({ js, tseslint, files, tsconfigRootDir, project, restrictedSyntax = [] }) {
  return [
    { ...js.configs.recommended, files },
    ...[...tseslint.configs.strictTypeChecked, ...tseslint.configs.stylisticTypeChecked].map((config) => ({ ...config, files })),
    {
      files,
      linterOptions: strictLinterOptions,
      languageOptions: { parserOptions: { project, tsconfigRootDir } },
      rules: {
        ...strictTypeScriptRules,
        'no-restricted-syntax': ['error', ...erasableSyntaxSelectors, ...restrictedSyntax],
      },
    },
  ];
}
