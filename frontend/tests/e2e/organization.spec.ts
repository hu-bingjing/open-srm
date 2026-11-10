import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { randomBytes, randomUUID } from 'node:crypto'
const suffix = randomUUID().slice(0, 8), password = randomBytes(24).toString('base64url'), login = `org-admin-${suffix}`, supplierLogin = `org-supplier-${suffix}`
let api: APIRequestContext, companyA: string, companyB: string, supplierId: string, otherSupplier: string, userId: string, userGrant: string, adminId: string
const companyName = `Company A ${suffix}`, companyBName = `Company B ${suffix}`, supplierName = `Supplier A ${suffix}`
async function command(path: string, method: string, data?: unknown, version?: number) {
  const token = (await (await api.get('/api/v1/auth/csrf')).json()).csrfToken
  const response = await api.fetch('/api/v1' + path, { method, data, headers: { 'X-CSRF-Token': token, 'Idempotency-Key': randomUUID(), ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) } })
  expect(response.ok(), `${method} ${path}: ${response.status()}`).toBeTruthy()
  return response.status() === 204 ? undefined : response.json()
}
async function get(path: string) { const r = await api.get('/api/v1' + path); expect(r.ok()).toBeTruthy(); return r.json() }
async function signIn(page: Page, user = login) { await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill(user); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录工作空间' }).click(); await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible() }
async function choose(page: Page, label: string, name: string) { await page.getByRole('combobox', { name: label, exact: true }).press('Enter'); await page.getByRole('option', { name, exact: true }).click(); await page.keyboard.press('Escape') }
test.describe.configure({ mode: 'serial' })
test.beforeAll(async ({ playwright }) => {
  api = await playwright.request.newContext({ baseURL: process.env.OPEN_SRM_E2E_URL || 'http://127.0.0.1:8080', ignoreHTTPSErrors: true })
  const original = JSON.parse(readFileSync('../private/initial-admin.json', 'utf8')) as { login: string; password: string }
  await command('/auth/login', 'POST', original)
  await command('/users', 'POST', { login, displayName: `Administrator ${suffix}`, password, roles: ['ADMIN'] })
  await command('/auth/logout', 'POST')
  await command('/auth/login', 'POST', { login, password }); adminId = (await get('/auth/me')).id
})
test.afterAll(async () => { await api?.dispose() })
test('company creation and editing persist after reload', async ({ page }) => {
  await signIn(page); await page.getByRole('button', { name: '公司管理', exact: true }).click()
  for (const [code, name] of [[`A-${suffix}`, companyName], [`B-${suffix}`, companyBName]]) {
    await page.getByRole('button', { name: '新建公司', exact: true }).click(); await page.getByLabel('业务编码').fill(code!); await page.getByLabel('业务名称').fill(name!); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByText('已保存', { exact: true })).toBeVisible(); await expect(page.getByRole('dialog')).toHaveCount(0)
  }
  const a = await get(`/companies?query=A-${suffix}`), b = await get(`/companies?query=B-${suffix}`); companyA = a.items[0].id; companyB = b.items[0].id
  await page.getByRole('row').filter({ hasText: companyName }).getByRole('button', { name: '编辑', exact: true }).click(); await page.getByLabel('时区').fill('UTC'); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); await page.reload(); await page.getByRole('button', { name: '公司管理', exact: true }).click(); await expect(page.getByRole('row').filter({ hasText: companyName })).toContainText('UTC')
})
test('plant and cross-company purchasing organization are persisted', async ({ page }) => {
  await signIn(page); await page.getByRole('button', { name: '工厂管理', exact: true }).click(); await page.getByRole('button', { name: '新建工厂' }).click(); await page.getByLabel('业务编码').fill(`P-${suffix}`); await page.getByLabel('业务名称').fill(`Plant A ${suffix}`); await choose(page, '所属公司', companyName); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: '采购组织', exact: true }).click(); await page.getByRole('button', { name: '新建采购组织' }).click(); await page.getByLabel('业务编码').fill(`O-${suffix}`); await page.getByLabel('业务名称').fill(`Purchasing A ${suffix}`); await choose(page, '关联公司', companyName); await choose(page, '关联公司', companyBName); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); await page.reload(); await page.getByRole('button', { name: '采购组织', exact: true }).click(); const row = page.getByRole('row').filter({ hasText: `O-${suffix}` }); await expect(row).toContainText(companyName); await expect(row).toContainText(companyBName)
})
test('supplier company sharing and freezing work through real APIs', async ({ page }) => {
  for (const companyId of [companyA, companyB]) { const u = await get(`/users/${adminId}`); await command(`/users/${adminId}/grants`, 'POST', { permission: 'supplier.manage', companyId }, u.version) }
  await signIn(page); await page.getByRole('button', { name: '供应商管理', exact: true }).click(); await page.getByRole('button', { name: '新建供应商' }).click(); await page.getByLabel('业务编码').fill(`S-${suffix}`); await page.getByLabel('业务名称').fill(supplierName); await choose(page, '关联公司', companyName); await choose(page, '关联公司', companyBName); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  supplierId = (await get(`/suppliers?query=S-${suffix}`)).items[0].id
  const row = page.getByRole('row').filter({ hasText: supplierName }); await row.getByRole('button', { name: '编辑', exact: true }).click(); await page.getByRole('combobox').last().press('Enter'); await page.getByRole('option', { name: '冻结', exact: true }).click(); await page.getByRole('button', { name: '保存', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0); await page.reload(); await page.getByRole('button', { name: '供应商管理', exact: true }).click(); await expect(row).toContainText('冻结')
  otherSupplier = (await command('/suppliers', 'POST', { code: `SB-${suffix}`, name: `Supplier B ${suffix}`, companyIds: [companyB] })).id
})
test('administrator grants company access and binds a supplier in the UI', async ({ page }) => {
  userId = (await command('/users', 'POST', { login: supplierLogin, displayName: `Supplier User ${suffix}`, password, roles: ['SUPPLIER'] })).id
  await signIn(page); await page.getByRole('button', { name: '用户与角色', exact: true }).click()
  await expect(page.locator('.el-table__body tr').first()).toBeVisible()
  const row = page.getByRole('row').filter({ hasText: supplierLogin })
  for (let i = 0; !(await row.count()) && i < 50; i++) await Promise.all([page.waitForResponse(r => r.url().includes('/api/v1/users?')), page.locator('button.btn-next').click()])
  await row.getByRole('button', { name: '数据授权' }).click()
  await page.getByRole('combobox').first().press('Enter'); await page.getByRole('option', { name: '供应商查看', exact: true }).click(); await choose(page, '授权公司', companyName); await page.getByRole('button', { name: '添加授权' }).click(); await expect(page.getByText('授权已保存', { exact: true })).toBeVisible()
  await page.getByRole('combobox').last().press('Enter'); await page.getByRole('option', { name: `S-${suffix} · ${supplierName}`, exact: true }).click(); await page.getByRole('button', { name: '保存绑定', exact: true }).click(); await expect(page.getByText('绑定已更新，该账号旧会话已失效')).toBeVisible()
  userGrant = (await get(`/users/${userId}/grants`))[0].id
})
test('supplier sees only its company and supplier, and revocation takes effect', async ({ page }) => {
  await signIn(page, supplierLogin); await page.getByRole('button', { name: '供应商管理', exact: true }).click(); await expect(page.getByRole('row').filter({ hasText: supplierName })).toBeVisible(); await expect(page.getByRole('row').filter({ hasText: companyBName })).toHaveCount(0); await expect(page.getByRole('button', { name: '新建供应商' })).toHaveCount(0)
  expect((await page.request.get(`/api/v1/suppliers/${otherSupplier}`)).status()).toBe(404)
  expect((await page.request.get(`/api/v1/companies/${companyB}`)).status()).toBe(404)
  const u = await get(`/users/${userId}`); await command(`/users/${userId}/grants/${userGrant}`, 'DELETE', undefined, u.version)
  await page.reload(); await page.getByRole('button', { name: '供应商管理', exact: true }).click(); await expect(page.getByText('暂无授权数据')).toBeVisible(); expect((await page.request.get(`/api/v1/suppliers/${supplierId}`)).status()).toBe(404)
})
