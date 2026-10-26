import { expect, test } from '@playwright/test'
import { authFile } from './users'

test.use({ storageState: authFile('gestor.nacional') })

const number = (s: string) => Number(s.replace(/\D/g, ''))

test('KPI cards show numbers and the live pill reaches Ao vivo', async ({ page }) => {
  await page.goto('/painel')
  await expect(page.getByRole('heading', { level: 1, name: 'Visão geral' })).toBeVisible()
  await expect(page.locator('.kpi-value').first()).toHaveText(/\d/)
  await expect(page.getByRole('status', { name: 'Atualização: Ao vivo' })).toBeVisible({ timeout: 30_000 })
})

test('Doses hoje grows while the simulator runs', async ({ page }) => {
  await page.goto('/painel')
  const value = page.locator('.kpi', { hasText: 'Doses hoje' }).locator('.kpi-value')
  await expect(value).toHaveText(/\d/)
  const first = number(await value.innerText())
  await expect
    .poll(async () => number(await value.innerText()), { timeout: 45_000, intervals: [1_000] })
    .toBeGreaterThan(first)
})
