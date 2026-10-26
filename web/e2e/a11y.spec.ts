import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { authFile } from './users'

test.use({ storageState: authFile('admin.dados') })

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']

for (const path of ['/painel', '/cobertura', '/alertas', '/campanhas', '/dados/execucoes']) {
  test(`axe: ${path} has no serious or critical violations`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    // The SSE stream keeps the network busy, so wait for the loading panels instead.
    await expect(page.locator('.state-loading')).toHaveCount(0)
    await page.waitForTimeout(1500)
    const { violations } = await new AxeBuilder({ page }).withTags(TAGS).analyze()
    const blocking = violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target) }))
    expect(blocking, JSON.stringify(blocking, null, 1)).toEqual([])
  })
}
