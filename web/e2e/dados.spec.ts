import { expect, test } from '@playwright/test'
import { authFile } from './users'

test.use({ storageState: authFile('admin.dados') })

test('lists the sources and runs a reconciliation with its log', async ({ page }) => {
  await page.goto('/dados/fontes')
  const sources = page.getByRole('table', { name: /Fontes da última carga/ })
  await expect(sources).toBeVisible()
  await expect(sources).toContainText(/malha/i)

  await page.goto('/dados/execucoes')
  const button = page.getByRole('button', { name: 'Reconciliar' })
  await expect(button).toBeEnabled({ timeout: 60_000 })
  await button.click()

  await page.getByRole('table').getByRole('link', { name: 'Reconciliação' }).first().click()
  await expect(page).toHaveURL(/\/dados\/execucoes\/.+/)
  await expect(page.getByRole('log', { name: 'Registro da execução' }).locator('.log-line').first()).toBeVisible({
    timeout: 60_000,
  })
  await expect(page.locator('dl.detail-list dd').first()).toHaveText('Concluída', { timeout: 60_000 })
})
