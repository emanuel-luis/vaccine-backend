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

// Headless Chromium needs software WebGL for the map tiles.
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
// The generated history covers 2024-2025; the simulator adds today's doses on top.
const today = new Date().toISOString().slice(0, 10)

async function session(user, colorScheme) {
  // Tall viewport and full-page shots keep chart axes and the map legend in frame.
  // Reduced motion makes the map jump to the selected municipality instead of flying.
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1100 },
    colorScheme,
    reducedMotion: 'reduce',
    baseURL: base,
    bypassCSP: true,
  })
  const page = await context.newPage()
  await page.goto('/painel')
  await page.locator('#username').fill(user)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await page.waitForURL(/\/painel/)
  return page
}

async function settle(page) {
  // The "+N doses" ticker depends on the clock and the simulator: keep it out of the pictures.
  await page.addStyleTag({ content: '.live-ticker{visibility:hidden}' })
  await page.getByRole('heading', { level: 1 }).waitFor()
  // The SSE stream keeps the network busy: wait for the loading panels, then let charts and tiles paint.
  await page.locator('.state-loading').first().waitFor({ state: 'detached' }).catch(() => {})
  await page.waitForTimeout(2000)
}

async function snap(page, name) {
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true })
  console.log(`wrote ${name}.png`)
}

async function shot(page, path, name) {
  await page.goto(path)
  await settle(page)
  await snap(page, name)
}

const digits = (s) => Number(s.replace(/\D/g, ''))

// Removes campaigns left behind by the e2e suite so they never show up in a picture.
async function dropE2eCampaigns(page) {
  for (;;) {
    await page.goto('/campanhas')
    await settle(page)
    const link = page.getByRole('link', { name: /^E2E campanha/ }).first()
    if ((await link.count()) === 0) return
    await link.click()
    await page.getByRole('button', { name: 'Excluir' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click()
    await page.waitForURL(/\/campanhas$/)
  }
}

// A fresh stack has no campaigns. The generated sample is about 0.2% of a birth cohort, so a round
// target would read as 0%: create with a placeholder, read the doses the campaign region and period
// really get, then set the target so the progress bar reads around 65%.
async function createCampaign(page) {
  await page.goto('/campanhas/nova')
  await page.getByLabel('Nome').fill('Multivacinação Bahia 2025')
  await page.getByLabel('Vacina').locator('option').nth(1).waitFor({ state: 'attached' })
  await page.getByLabel('Vacina').selectOption({ index: 1 })
  await page.getByLabel('Região').selectOption('UF:BA')
  await page.getByLabel('Início').fill('2025-01-01')
  await page.getByLabel('Fim').fill('2025-12-31')
  await page.getByLabel('Meta de doses').fill('1000')
  await page.getByRole('button', { name: 'Salvar' }).click()
  await page.waitForURL(/\/campanhas\/[0-9a-f-]{36}$/)
  await settle(page)
  const doses = digits(await page.locator('.kpi', { hasText: 'Doses até hoje' }).locator('.kpi-value').innerText())
  if (!doses) throw new Error('The campaign region and period have no doses: is the stack loaded?')
  await page.getByRole('link', { name: 'Editar' }).click()
  await page.getByLabel('Meta de doses').fill(String(Math.round(doses / 0.65)))
  await page.getByRole('button', { name: 'Salvar' }).click()
  await page.waitForURL(/\/campanhas\/[0-9a-f-]{36}$/)
}

const national = await session('gestor.nacional', 'light')
await national.getByRole('status', { name: 'Atualização: Ao vivo' }).waitFor({ timeout: 30_000 })
await shot(national, `/painel?de=2025-01-01&ate=${today}`, 'painel')
await shot(national, '/cobertura?ano=2025', 'cobertura-br')

await national.goto('/cobertura/BA/2927408?ano=2025')
await settle(national)
await national.getByRole('table', { name: 'Unidades de saúde do município' }).locator('tbody tr').first().waitFor()
await snap(national, 'cobertura-salvador-unidades')

await shot(national, '/alertas?ano=2025', 'alertas')

await dropE2eCampaigns(national)
await national.goto('/campanhas')
await settle(national)
const first = national.locator('tbody tr th a').first()
if ((await first.count()) === 0) await createCampaign(national)
else await first.click()
await settle(national)
await snap(national, 'campanha-detalhe')
await national.context().close()

const dark = await session('gestor.nacional', 'dark')
await dark.getByRole('status', { name: 'Atualização: Ao vivo' }).waitFor({ timeout: 30_000 })
await shot(dark, `/painel?de=2025-01-01&ate=${today}`, 'painel-dark')
await dark.context().close()

const admin = await session('admin.dados', 'light')
await admin.goto('/dados/execucoes')
await settle(admin)
if ((await admin.locator('tbody tr th a').count()) === 0) {
  await admin.getByRole('button', { name: 'Reconciliar' }).click()
  await admin.locator('tbody tr th a').first().waitFor()
}
await admin.locator('tbody tr th a').first().click()
await admin.getByRole('log').locator('.log-line').first().waitFor({ timeout: 60_000 })
await admin.locator('dl.detail-list dd').first().filter({ hasText: /Concluída|Falhou/ }).waitFor({ timeout: 90_000 })
await settle(admin)
await snap(admin, 'dados-execucao')

await browser.close()
