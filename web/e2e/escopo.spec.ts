import { expect, test } from '@playwright/test'
import { authFile } from './users'

test.use({ storageState: authFile('profissional.salvador') })

test('a professional is held to the Salvador scope', async ({ page }) => {
  await page.goto('/painel')
  await expect(page.locator('.badge')).toHaveText('Município: 2927408')

  await page.goto('/cobertura')
  await expect(page).toHaveURL(/\/cobertura\/BA\/2927408/)
  await expect(page.getByRole('navigation', { name: 'Navegação por região' }).getByRole('link')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Exportar CSV' })).toHaveCount(0)

  await page.goto('/dados/execucoes')
  await expect(page).toHaveURL(/\/acesso-negado/)
  await expect(page.getByRole('heading', { level: 1, name: 'Acesso negado' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Execuções' })).toHaveCount(0)
})
