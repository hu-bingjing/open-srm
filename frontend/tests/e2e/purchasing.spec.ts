import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { randomBytes, randomUUID } from 'node:crypto'
const suffix = randomUUID().slice(0, 8), password = randomBytes(24).toString('base64url'), login = `po-admin-${suffix}`, externalLogin = `po-supplier-${suffix}`
const companyName = `Company PO ${suffix}`, orgName = `Purchasing PO ${suffix}`, supplierName = `Supplier PO ${suffix}`, plantNames = [`Plant PO A ${suffix}`, `Plant PO B ${suffix}`], connectionName = `Mock PO ${suffix}`
let api: APIRequestContext, companyId: string, purchasingOrgId: string, supplierId: string, plantIds: string[], adminId: string, connectionId: string, orderId: string
async function get(path: string) { const r = await api.get('/api/v1' + path); expect(r.ok()).toBeTruthy(); return r.json() }
async function command(path: string, method: string, data?: unknown, version?: number) {
 const token = (await get('/auth/csrf')).csrfToken
 const r = await api.fetch('/api/v1' + path, { method, data, headers: { 'X-CSRF-Token': token, 'Idempotency-Key': randomUUID(), ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) } }); expect(r.ok(), `${method} ${path}: ${r.status()}`).toBeTruthy(); return r.status() === 204 ? undefined : r.json()
}
async function grant(user: string, permission: string, plantId?: string) { const u = await get('/users/' + user); await command(`/users/${user}/grants`, 'POST', { permission, companyId, plantId: plantId || null, purchasingOrgId: null }, u.version) }
async function choose(page: Page, label: string, value: string) { await page.getByRole('combobox', { name: label, exact: true }).press('Enter'); await page.getByRole('option', { name: value, exact: true }).click(); await page.keyboard.press('Escape') }
async function signIn(page: Page, account = login) { await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill(account); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录工作空间' }).click(); await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible() }
async function launch(page: Page, scenario: string, label: string, terminal = 'SUCCEEDED') {
 await choose(page, '同步连接', connectionName); await choose(page, '同步任务类型', label); await page.getByRole('button', { name: '启动同步', exact: true }).click(); await expect(page.getByText('同步任务已提交', { exact: true })).toBeVisible()
 let result: any
 await expect.poll(async () => { const v = await get('/sync-jobs?size=100'); result = v.items.find((j: any) => j.connectionId === connectionId && j.scenario === scenario); return result?.status }, { timeout: 15000 }).toBe(terminal)
 await page.getByRole('button', { name: '刷新任务' }).click(); return result
}
test.describe.configure({ mode: 'serial' })
test.beforeAll(async ({ playwright }) => {
 api = await playwright.request.newContext({ baseURL: process.env.OPEN_SRM_E2E_URL || 'http://127.0.0.1:8080', ignoreHTTPSErrors: true })
 await command('/auth/login', 'POST', JSON.parse(readFileSync('../private/initial-admin.json', 'utf8')))
 await command('/users', 'POST', { login, displayName: `Order Administrator ${suffix}`, password, roles: ['ADMIN'] }); await command('/auth/logout', 'POST'); await command('/auth/login', 'POST', { login, password }); adminId = (await get('/auth/me')).id
 companyId = (await command('/companies', 'POST', { code: `CPO-${suffix}`, name: companyName, timezone: 'UTC', active: true })).id
 plantIds = []; for (let n = 0; n < 2; n++) plantIds.push((await command('/plants', 'POST', { code: `PPO${n}-${suffix}`, name: plantNames[n], companyId, active: true })).id)
 purchasingOrgId = (await command('/purchasing-organizations', 'POST', { code: `OPO-${suffix}`, name: orgName, companyIds: [companyId], active: true })).id
 for (const permission of ['supplier.manage', 'sync.manage', 'order.read']) await grant(adminId, permission)
 supplierId = (await command('/suppliers', 'POST', { code: `SPO-${suffix}`, name: supplierName, companyIds: [companyId] })).id
})
test.afterAll(async () => { await api?.dispose() })
test('operator creates a real mapped Mock connection', async ({ page }) => {
 await signIn(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); await page.getByRole('button', { name: '新建连接', exact: true }).click(); await page.getByLabel('连接名称', { exact: true }).fill(connectionName)
 await choose(page, '映射公司', companyName); await choose(page, '映射采购组织', orgName); await choose(page, '映射供应商', supplierName); await choose(page, '映射工厂', plantNames[0]!); await choose(page, '映射工厂', plantNames[1]!)
 await page.getByRole('button', { name: '建立连接', exact: true }).click(); await expect(page.getByText('连接已建立', { exact: true })).toBeVisible()
 connectionId = (await get('/erp-connections?size=100')).items.find((c: any) => c.name === connectionName).id
 // This runs on the deployment host; never export this private acceptance account.
 writeFileSync('../private/phase03-test.json', JSON.stringify({ login, password, connectionId, companyId, supplierId, plantIds }), { mode: 0o600 })
})
test('initial sync persists order headers lines schedules and monitor counts', async ({ page }) => {
 await signIn(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); const j = await launch(page, 'INITIAL', '初始订单'); expect(j.applied).toBe(4)
 await page.getByRole('button', { name: '采购订单', exact: true }).click(); const row = page.getByRole('row').filter({ hasText: 'PO-1001' }); await expect(row).toBeVisible(); await row.getByRole('button', { name: '订单与版本' }).click(); await expect(page.getByRole('heading', { name: '当前授权行与计划行' })).toBeVisible(); await expect(page.getByRole('dialog').locator('.el-table').first().getByText('60.000000', { exact: true })).toHaveCount(2)
 const v = await get(`/purchase-orders?companyId=${companyId}&query=PO-1001`); orderId = v.items[0].id; expect(v.items[0].items.length).toBe(2); expect(v.items[0].items[0].schedules.length).toBe(2)
})
test('new revision is visible while old revision stays unchanged', async ({ page }) => {
 await signIn(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); expect((await launch(page, 'UPDATE', '订单新版本')).applied).toBe(4)
 await page.getByRole('button', { name: '采购订单', exact: true }).click(); await page.getByRole('row').filter({ hasText: 'PO-1001' }).getByRole('button', { name: '订单与版本' }).click(); await expect(page.getByText(/mock-r1 ·/)).toBeVisible(); await expect(page.getByRole('dialog').locator('.el-table').first().getByText('55.000000', { exact: true })).toHaveCount(2)
 const revisions = await get(`/purchase-orders/${orderId}/revisions`); expect(revisions.total).toBe(2); expect(revisions.items[1].items[0].schedules[0].orderedQuantity).toBe('60.000000')
})
test('same-version conflict is isolated without overwriting order', async ({ page }) => {
 await signIn(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); const j = await launch(page, 'CONFLICT', '同版本内容冲突', 'QUARANTINED'); expect(j.quarantined).toBe(1)
 await page.getByRole('row').filter({ hasText: '同版本内容冲突' }).getByRole('button', { name: '检查点与异常' }).click(); await expect(page.getByText('SOURCE_REVISION_CONFLICT', { exact: true })).toBeVisible(); expect((await get(`/purchase-orders/${orderId}`)).sourceRevision).toBe('mock-r2')
})
test('failed page resumes from checkpoint through the UI', async ({ page }) => {
 await signIn(page); await page.getByRole('button', { name: 'ERP 同步监控', exact: true }).click(); const j = await launch(page, 'FAIL_ONCE', '分页读取一次故障', 'FAILED'); expect(j.processed).toBe(2); expect(j.nextCursor).toBeTruthy()
 await page.getByRole('row').filter({ hasText: '分页读取一次故障' }).getByRole('button', { name: '重试', exact: true }).click(); await expect.poll(async () => (await get('/sync-jobs/' + j.id)).status, { timeout: 15000 }).toBe('SUCCEEDED'); const v = await get('/sync-jobs/' + j.id); expect(v.processed).toBe(4); expect(v.applied).toBe(0); expect(v.skipped).toBe(4)
})
test('supplier sees only authorized plant lines and filtered history', async ({ page }) => {
 const user = await command('/users', 'POST', { login: externalLogin, displayName: `Order Supplier ${suffix}`, password, roles: ['SUPPLIER'] }); await grant(user.id, 'order.read', plantIds[0]); const latest = await get('/users/' + user.id); await command(`/users/${user.id}/supplier-binding`, 'PUT', { supplierId }, latest.version)
 await signIn(page, externalLogin); await page.getByRole('button', { name: '采购订单', exact: true }).click(); await expect(page.getByRole('row').filter({ hasText: 'PO-1002' })).toHaveCount(0); await expect(page.getByRole('row').filter({ hasText: 'PO-1001' })).toBeVisible(); await expect(page.getByRole('button', { name: 'ERP 同步监控', exact: true })).toHaveCount(0)
 const response = await page.request.get('/api/v1/purchase-orders/' + orderId); expect(response.ok()).toBeTruthy(); expect((await response.json()).items.length).toBe(1)
 const history = await (await page.request.get(`/api/v1/purchase-orders/${orderId}/revisions`)).json(); expect(history.items.every((r: any) => r.items.length === 1)).toBeTruthy()
})
