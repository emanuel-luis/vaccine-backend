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
  const links = page.getByRole('table').getByRole('link', { name: 'Reconciliação' })
  const previous = await links.first().getAttribute('href')
  await button.click()

  // Newest first: wait until the top row is a new job, then open it (not an older reconciliation).
  await expect(links.first()).not.toHaveAttribute('href', previous ?? '')
  await links.first().click()
  await expect(page).toHaveURL(/\/dados\/execucoes\/.+/)
  await expect(page.getByRole('log', { name: 'Registro da execução' }).locator('.log-line').first()).toBeVisible({
    timeout: 60_000,
  })
  await expect(page.locator('dl.detail-list dd').first()).toHaveText('Concluída', { timeout: 60_000 })
})
