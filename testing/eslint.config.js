import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';
import { defineConfig } from 'eslint/config';
import { strictLinterOptions, strictTypeScriptConfigs } from '../eslint.strict.mjs';

export default defineConfig(
  { linterOptions: strictLinterOptions },
  strictTypeScriptConfigs({
    js: eslint,
    tseslint,
    files: ['src/**/*.ts'],
    tsconfigRootDir: import.meta.dirname,
    project: ['./tsconfig.json'],
  }),
  {
    files: ['src/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['eslint.config.js'],
    extends: [eslint.configs.recommended],
    languageOptions: { globals: globals.node },
  },
);
