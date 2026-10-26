import type { Page } from '@playwright/test'

export const USERS = ['gestor.nacional', 'gestor.bahia', 'profissional.salvador', 'admin.dados'] as const
export type DemoUser = (typeof USERS)[number]

// The OIDC user lives in sessionStorage, which storageState does not keep. The Keycloak SSO cookies
// it does keep sign the browser in again on the first navigation, still through the real flow.
export const authFile = (user: DemoUser) => `e2e/.auth/${user}.json`

export function demoPassword(): string {
  const p = process.env.KC_DEMO_PASSWORD
  if (!p) throw new Error('Set KC_DEMO_PASSWORD (the demo users password from .env)')
  return p
}

/** Signs in through the real Keycloak form; the app lands back on its origin. */
export async function login(page: Page, user: DemoUser): Promise<void> {
  await page.goto('/painel')
  await page.locator('#username').fill(user)
  await page.locator('#password').fill(demoPassword())
  await page.locator('#kc-login').click()
  await page.waitForURL(/\/painel/)
  await page.getByRole('heading', { level: 1 }).waitFor()
}
