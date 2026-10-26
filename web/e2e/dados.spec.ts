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
  // Open the job this click created, read from the POST response, not from the list: the list can
  // still be loading, already hold older reconciliations on a reused stack, or be capped at 20 rows.
  const created = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/admin/jobs') && r.status() === 202,
  )
  await button.click()
  const { data } = (await (await created).json()) as { data: { jobId: string } }
  await page.goto(`/dados/execucoes/${data.jobId}`)
  await expect(page).toHaveURL(/\/dados\/execucoes\/.+/)
  await expect(page.getByRole('log', { name: 'Registro da execução' }).locator('.log-line').first()).toBeVisible({
    timeout: 60_000,
  })
  await expect(page.locator('dl.detail-list dd').first()).toHaveText('Concluída', { timeout: 60_000 })
})
