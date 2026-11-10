import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests/e2e',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 30000,
  outputDir: '../tmp/playwright-results',
  reporter: [['list'], ['json', { outputFile: '../tmp/playwright-results/report.json' }]],
  use: { baseURL: process.env.OPEN_SRM_E2E_URL || 'http://127.0.0.1:8080',
    launchOptions: process.env.OPEN_SRM_E2E_BROWSER_PATH ? { executablePath: process.env.OPEN_SRM_E2E_BROWSER_PATH } : {},
    ignoreHTTPSErrors: true, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
})
