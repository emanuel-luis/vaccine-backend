// Writes the README screenshots to ../docs/screenshots against the running stack (`make demo`).
// Not a test: `npm run screenshots` (needs KC_DEMO_PASSWORD, optional E2E_BASE_URL).
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from '@playwright/test'

const base = process.env.E2E_BASE_URL ?? 'http://localhost:8081'
const password = process.env.KC_DEMO_PASSWORD
if (!password) throw new Error('Set KC_DEMO_PASSWORD (the demo users password from .env)')
const out = resolve(dirname(fileURLToPath(import.meta.url)), '../../docs/screenshots')
mkdirSync(out, { recursive: true })

const browser = await chromium.launch()

async function session(user, colorScheme) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme, baseURL: base })
  const page = await context.newPage()
  await page.goto('/painel')
  await page.locator('#username').fill(user)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await page.waitForURL(/\/painel/)
  // The "+N doses" ticker depends on the clock and the simulator: keep it out of the pictures.
  await page.addStyleTag({ content: '.live-ticker{visibility:hidden}' })
  return page
}

async function settle(page) {
  await page.getByRole('heading', { level: 1 }).waitFor()
  // The SSE stream keeps the network busy: wait for the loading panels, then let charts and tiles paint.
  await page.locator('.state-loading').first().waitFor({ state: 'detached' }).catch(() => {})
  await page.waitForTimeout(1500)
}

async function shot(page, path, name) {
  await page.goto(path)
  await page.addStyleTag({ content: '.live-ticker{visibility:hidden}' })
  await settle(page)
  await page.screenshot({ path: `${out}/${name}.png` })
  console.log(`wrote ${name}.png`)
}

for (const scheme of ['light', 'dark']) {
  const page = await session('gestor.nacional', scheme)
  const suffix = scheme === 'dark' ? '-dark' : ''
  await page.getByRole('status', { name: 'Atualização: Ao vivo' }).waitFor({ timeout: 30_000 })
  await shot(page, '/painel', `painel${suffix}`)
  await shot(page, '/cobertura/BA', `cobertura-ba${suffix}`)
  if (scheme === 'light') {
    await shot(page, '/cobertura/BA/2927408', 'cobertura-salvador-unidades')
    await shot(page, '/alertas', 'alertas')
    await page.goto('/campanhas')
    await settle(page)
    const first = page.locator('tbody tr th a').first()
    if ((await first.count()) === 0) throw new Error('No campaign to photograph: create one first')
    await first.click()
    await page.addStyleTag({ content: '.live-ticker{visibility:hidden}' })
    await settle(page)
    await page.screenshot({ path: `${out}/campanha-detalhe.png` })
    console.log('wrote campanha-detalhe.png')
  }
  await page.context().close()
}

const admin = await session('admin.dados', 'light')
await admin.goto('/dados/execucoes')
await settle(admin)
if ((await admin.locator('tbody tr th a').count()) === 0) {
  await admin.getByRole('button', { name: 'Reconciliar' }).click()
  await admin.locator('tbody tr th a').first().waitFor()
}
await admin.locator('tbody tr th a').first().click()
await admin.addStyleTag({ content: '.live-ticker{visibility:hidden}' })
await admin.getByRole('log').locator('.log-line').first().waitFor({ timeout: 60_000 })
await admin.locator('dl.detail-list dd').first().filter({ hasText: /Concluída|Falhou/ }).waitFor({ timeout: 90_000 })
await settle(admin)
await admin.screenshot({ path: `${out}/dados-execucao.png` })
console.log('wrote dados-execucao.png')

await browser.close()
