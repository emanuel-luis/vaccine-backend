import { expect, test } from '@playwright/test'
import { authFile } from './users'

test.use({ storageState: authFile('gestor.nacional') })

test('drills Brasil, Bahia, Salvador and the unit table with the keyboard', async ({ page }) => {
  await page.goto('/cobertura')
  await expect(page.getByRole('heading', { level: 1, name: 'Mapa de cobertura' })).toBeVisible()
  await expect(page.locator('.maplibregl-canvas').first()).toBeVisible()
  await expect(page.getByRole('list', { name: 'Legenda' })).toContainText('< 5')

  const areas = page.getByRole('navigation', { name: 'Áreas do mapa' })
  await areas.getByRole('button', { name: /^Bahia/ }).focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/cobertura\/BA(\?|$)/)

  await areas.getByRole('button', { name: /^Salvador/ }).focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/cobertura\/BA\/2927408/)
  await expect(page.getByRole('heading', { level: 2, name: /Unidades de saúde/ })).toBeVisible()
  await expect(
    page.getByRole('table', { name: 'Unidades de saúde do município' }).locator('tbody tr').first(),
  ).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Navegação por região' })).toContainText('Salvador')
})
