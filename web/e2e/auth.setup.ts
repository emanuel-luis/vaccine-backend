import { test as setup } from '@playwright/test'
import { authFile, login, USERS } from './users'

for (const user of USERS) {
  setup(`sign in as ${user}`, async ({ page }) => {
    await login(page, user)
    await page.context().storageState({ path: authFile(user) })
  })
}
