import { test, expect } from '@playwright/test'
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'

// Run only on a dedicated Phase 01 TEST stack; no reset, deletion or real business data.
const privateDir = resolve('../private')
const credentialsPath = resolve(privateDir, 'initial-admin.json')
if (!existsSync(credentialsPath)) {
  mkdirSync(privateDir, { recursive: true, mode: 0o700 })
  writeFileSync(credentialsPath, JSON.stringify({ login: 'admin', password: randomBytes(24).toString('base64url') }), { flag: 'wx', mode: 0o600 })
}
const account = JSON.parse(readFileSync(credentialsPath, 'utf8')) as { login: string; password: string }
const runUser = `test-${randomUUID().slice(0, 12)}`
const runPassword = randomBytes(24).toString('base64url')
test.describe.configure({ mode: 'serial' })

test('first setup creates an administrator, then closes setup', async ({ page, request }) => {
  const response = await request.get('/api/v1/auth/bootstrap/status')
  expect(response.ok()).toBeTruthy()
  if (!(await response.json()).setupRequired) {
    // Repeated acceptance uses the previously generated private test administrator.
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '登录 OpenSRM' })).toBeVisible()
    return
  }
  const bootstrap = readFileSync(resolve(privateDir, 'bootstrap-token'), 'utf8').trim()
  await page.goto('/')
  await page.getByLabel('初始化令牌', { exact: true }).fill(bootstrap)
  await page.getByLabel('管理员账号', { exact: true }).fill(account.login)
  await page.getByLabel('显示名称', { exact: true }).fill('Administrator A')
  await page.getByLabel('管理员密码', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: '创建管理员', exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录 OpenSRM' })).toBeVisible()
  expect((await (await request.get('/api/v1/auth/bootstrap/status')).json()).setupRequired).toBeFalsy()
})

test('login persists through refresh and profile is really saved', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill(account.login)
  await page.getByLabel('密码', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: '登录工作空间' }).click()
  await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible()
  const previousName = await page.getByLabel('个人显示名称').inputValue()
  const updatedName = previousName === 'Administrator A' ? 'Administrator A Updated' : 'Administrator A'
  await page.getByLabel('个人显示名称').fill(updatedName)
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(page.getByText('个人资料已保存')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('个人显示名称')).toHaveValue(updatedName)
  await page.screenshot({ path: '../tmp/playwright-results/workspace.png', fullPage: true })
  const cookies = await page.context().cookies()
  const session = cookies.find(c => c.name === 'OPEN_SRM_SESSION')
  expect(session?.httpOnly).toBeTruthy()
  if (new URL(page.url()).protocol === 'https:') expect(session?.secure).toBeTruthy()
  expect(await page.evaluate(() => localStorage.length)).toBe(0)
})

test('administrator creates a persisted account and manages its status', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill(account.login)
  await page.getByLabel('密码', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: '登录工作空间' }).click()
  await page.getByRole('button', { name: '用户与角色' }).click()
  await page.getByRole('button', { name: '新建用户' }).click()
  await page.getByLabel('新用户账号').fill(runUser)
  await page.getByLabel('新用户名称').fill('User A')
  await page.getByLabel('新用户密码').fill(runPassword)
  await page.getByRole('button', { name: '创建用户', exact: true }).click()
  await expect(page.getByText('用户已创建')).toBeVisible()
  const row = page.getByRole('row').filter({ hasText: runUser })
  for (let i = 0; !(await row.count()) && i < 50; i++) await Promise.all([page.waitForResponse(r => r.url().includes('/api/v1/users?')), page.locator('button.btn-next').click()])
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: '禁用', exact: true }).click()
  await expect(row.getByText('禁用', { exact: true })).toBeVisible()
  await row.getByRole('button', { name: '启用', exact: true }).click()
  await expect(row.getByText('有效', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../tmp/playwright-results/users.png', fullPage: true })
})

test('ordinary account has no admin navigation and cannot read users', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill(runUser)
  await page.getByLabel('密码', { exact: true }).fill(runPassword)
  await page.getByRole('button', { name: '登录工作空间' }).click()
  await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible()
  await expect(page.getByRole('button', { name: '用户与角色' })).toHaveCount(0)
  expect((await page.request.get('/api/v1/users')).status()).toBe(403)
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录 OpenSRM' })).toBeVisible()
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)
})
