import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

// Metadata-only acceptance against an explicitly configured development/test connector.
const suffix = randomUUID().slice(0, 8), initialAccount = JSON.parse(readFileSync('../private/initial-admin.json', 'utf8'))
const account = { login: `sap-admin-${suffix}`, password: randomUUID() + randomUUID() }
const name = `SAP metadata pilot ${suffix}`
let api: APIRequestContext, connection: string, company: string, plant: string, org: string, supplier: string
async function get(path: string) { const r = await api.get('/api/v1' + path); expect(r.ok()).toBeTruthy(); return r.json() }
async function post(path: string, data: unknown, version?: number) {
  const csrf = await get('/auth/csrf')
  const r = await api.post('/api/v1' + path, { data, headers: { 'X-CSRF-Token': csrf.csrfToken, 'Idempotency-Key': randomUUID(), ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) } })
  expect(r.ok(), path).toBeTruthy(); return r.status() === 204 ? undefined : r.json()
}
async function login(page: Page) { await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill(account.login); await page.getByLabel('密码', { exact: true }).fill(account.password); await page.getByRole('button', { name: '登录工作空间' }).click(); await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible() }
async function choose(page: Page, label: string, value: string) { await page.getByRole('combobox', { name: label, exact: true }).press('Enter'); await page.getByRole('option', { name: value, exact: true }).click() }
test.describe.configure({ mode: 'serial' })
test.beforeAll(async ({ playwright }) => {
  api = await playwright.request.newContext({ baseURL: process.env.OPEN_SRM_E2E_URL || 'http://127.0.0.1:8080', ignoreHTTPSErrors: true })
  await post('/auth/login', initialAccount)
  const capability = (await get('/erp-adapters')).find((a: any) => a.adapterType === 'sap-odata')
  test.skip(!capability?.configured, 'SAP is optional; this suite requires explicitly provisioned private test configuration')
  expect(capability.writeEnabled).toBe(false)
  await post('/users', { ...account, displayName: `SAP Test Administrator ${suffix}`, roles: ['ADMIN'] })
  await post('/auth/logout', {})
  await post('/auth/login', account)
  expect((await get('/erp-adapters')).find((a: any) => a.adapterType === 'sap-odata')).toEqual({ adapterType: 'sap-odata', configured: true, writeEnabled: false })
  company = (await post('/companies', { code: `SAP-${suffix}`, name: `Company SAP ${suffix}`, timezone: 'UTC', active: true })).id
  plant = (await post('/plants', { code: `SAP-${suffix}`, name: `Plant SAP ${suffix}`, companyId: company, active: true })).id
  org = (await post('/purchasing-organizations', { code: `SAP-${suffix}`, name: `Purchasing SAP ${suffix}`, companyIds: [company], active: true })).id
  const user = await get('/auth/me')
  for (const permission of ['supplier.manage', 'sync.manage']) { const current = await get(`/users/${user.id}`); await post(`/users/${user.id}/grants`, { permission, companyId: company, plantId: null, purchasingOrgId: null }, current.version) }
  supplier = (await post('/suppliers', { code: `SAP-${suffix}`, name: `Supplier SAP ${suffix}`, companyIds: [company] })).id
  connection = (await post('/erp-connections', { name, adapterType: 'sap-odata', companyId: company, purchasingOrgId: org, supplierId: supplier, plantIds: [plant], sourceSystem: 'SAP_SANDBOX', sourceClient: '100', companyExternalKey: 'UNCONFIGURED', purchasingOrgExternalKey: 'UNCONFIGURED', supplierExternalKey: 'UNCONFIGURED', plantMappings: { UNCONFIGURED: plant } })).id
})
test.afterAll(async () => { await api?.dispose() })
test('SAP metadata task runs through the real page and persists success', async ({ page }) => {
  await login(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click()
  await choose(page, '同步连接', name); await choose(page, '同步任务类型', '验证接口元数据')
  await page.getByRole('button', { name: '启动同步', exact: true }).click()
  await expect.poll(async () => (await get('/sync-jobs?size=100')).items.find((j: any) => j.connectionId === connection && j.scenario === 'SAP_METADATA')?.status, { timeout: 60000 }).toBe('SUCCEEDED')
  await page.getByRole('button', { name: '刷新任务' }).click(); await expect(page.getByRole('row').filter({ hasText: name })).toContainText('完成')
  await page.reload(); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); await expect(page.getByRole('row').filter({ hasText: name })).toContainText('完成')
})
test('SAP pilot without configured organization and order scope fails closed', async ({ page }) => {
  await login(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); await choose(page, '同步连接', name); await choose(page, '同步任务类型', '读取授权采购订单'); await page.getByRole('button', { name: '启动同步', exact: true }).click()
  await expect.poll(async () => (await get('/sync-jobs?size=100')).items.find((j: any) => j.connectionId === connection && j.scenario === 'SAP_READ')?.errorCode, { timeout: 15000 }).toBe('SAP_SCOPE_NOT_CONFIGURED')
  await page.getByRole('button', { name: '刷新任务' }).click(); await expect(page.getByRole('row').filter({ hasText: name }).filter({ hasText: 'SAP_SCOPE_NOT_CONFIGURED' })).toBeVisible()
})
