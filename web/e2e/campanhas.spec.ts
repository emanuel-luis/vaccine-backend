import { expect, test } from '@playwright/test'
import { authFile } from './users'

const name = `E2E campanha ${Date.now()}`

test.describe('gestor.bahia', () => {
  test.use({ storageState: authFile('gestor.bahia') })

  test('creates, lists, opens and deletes a campaign', async ({ page }) => {
    await page.goto('/campanhas/nova')
    await page.getByLabel('Nome').fill(name)
    const vaccine = page.getByLabel('Vacina')
    await expect(vaccine.locator('option')).not.toHaveCount(1)
    await vaccine.selectOption({ index: 1 })
    await page.getByLabel('Região').selectOption('UF:BA')
    await page.getByLabel('Início').fill('2026-01-01')
    await page.getByLabel('Fim').fill('2026-12-31')
    await page.getByLabel('Meta de doses').fill('1000')
    await page.getByRole('button', { name: 'Salvar' }).click()

    await expect(page).toHaveURL(/\/campanhas\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()

    await page.goto('/campanhas')
    await page.getByRole('link', { name }).click()
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()

    await page.getByRole('button', { name: 'Excluir' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click()
    await expect(page).toHaveURL(/\/campanhas$/)
    await expect(page.getByRole('link', { name })).toHaveCount(0)
  })
})

test.describe('profissional.salvador', () => {
  test.use({ storageState: authFile('profissional.salvador') })

  test('has no create, edit or delete controls', async ({ page }) => {
    await page.goto('/campanhas')
    await expect(page.getByRole('heading', { level: 1, name: 'Campanhas' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Nova campanha' })).toHaveCount(0)
    const first = page.locator('tbody tr th a').first()
    if (await first.count()) {
      await first.click()
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      await expect(page.getByRole('link', { name: 'Editar' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Excluir' })).toHaveCount(0)
    }
  })
})
