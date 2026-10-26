import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'test-results', 'playwright-report', 'src/api/schema.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  reactHooks.configs.flat['recommended-latest'],
  { languageOptions: { globals: globals.browser } },
  { files: ['*.config.{js,ts}', 'scripts/**/*.mjs'], languageOptions: { globals: globals.node } },
)
