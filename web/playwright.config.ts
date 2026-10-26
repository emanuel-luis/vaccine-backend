import { defineConfig, devices } from '@playwright/test'

// Runs against the live compose stack (`make demo`); nothing here starts it.
export default defineConfig({
  testDir: 'e2e',
  testMatch: /.*\.(spec|setup)\.ts/,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 2,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8081',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
      // A failed login must not record the demo password into the uploaded report.
      use: { trace: 'off', screenshot: 'off', video: 'off' },
    },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['setup'],
      testIgnore: /auth\.setup\.ts/,
    },
  ],
})
