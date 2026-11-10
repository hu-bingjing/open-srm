import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
const suffix = randomUUID().slice(0, 8), password = randomUUID() + randomUUID()
const adminLogin = `ws-admin-${suffix}`, supplierLogin = `ws-supplier-${suffix}`, buyerLogin = `ws-buyer-${suffix}`
let api: APIRequestContext, company: string, connection: string, orderId: string, noticeId: string
async function get(path: string) { const r = await api.get('/api/v1' + path); expect(r.ok(), path).toBeTruthy(); return r.json() }
async function command(path: string, data?: unknown, version?: number, method = 'POST') {
 const csrf = await get('/auth/csrf'); const r = await api.fetch('/api/v1' + path, { method, data, headers: { 'X-CSRF-Token': csrf.csrfToken, 'Idempotency-Key': randomUUID(), ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) } }); expect(r.ok(), `${path} ${r.status()}`).toBeTruthy(); return r.status() === 204 ? undefined : r.json()
}
async function apiLogin(login: string) { await command('/auth/logout'); await command('/auth/login', { login, password }) }
async function login(page: Page, login: string) { const csrf = await (await page.request.get('/api/v1/auth/csrf')).json(); await page.request.post('/api/v1/auth/logout', { headers: { 'X-CSRF-Token': csrf.csrfToken } }); await page.goto('/'); await page.getByLabel('账号', { exact: true }).fill(login); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录工作空间' }).click(); await expect(page.getByRole('heading', { name: '我的工作空间' })).toBeVisible() }
async function sync(scenario: string) { const j = await command('/sync-jobs', { connectionId: connection, entityType: 'PURCHASE_ORDER', scenario }); await expect.poll(async () => (await get('/sync-jobs/' + j.id)).status, { timeout: 25000 }).toBe(scenario === 'PERMANENT_FAIL' ? 'FAILED' : 'SUCCEEDED') }
test.describe.configure({ mode: 'serial' })
test.beforeAll(async ({ playwright }) => {
 api = await playwright.request.newContext({ baseURL: process.env.OPEN_SRM_E2E_URL || 'http://127.0.0.1:8080', ignoreHTTPSErrors: true }); await command('/auth/login', JSON.parse(readFileSync('../private/initial-admin.json', 'utf8')))
 const a = await command('/users', { login: adminLogin, displayName: `Workspace Administrator ${suffix}`, password, roles: ['ADMIN'] }); await apiLogin(adminLogin)
 company = (await command('/companies', { code: `WS-${suffix}`, name: `Company Workspace ${suffix}`, timezone: 'UTC', active: true })).id
 const plant = (await command('/plants', { code: `WSP-${suffix}`, name: `Plant Workspace ${suffix}`, companyId: company, active: true })).id, org = (await command('/purchasing-organizations', { code: `WSO-${suffix}`, name: `Purchasing Workspace ${suffix}`, companyIds: [company], active: true })).id
 async function grant(id: string, permission: string) { await command(`/users/${id}/grants`, { permission, companyId: company }, (await get('/users/' + id)).version) }
 for (const permission of ['supplier.manage', 'sync.manage', 'order.read']) await grant(a.id, permission)
 const supplier = (await command('/suppliers', { code: `WSS-${suffix}`, name: `Supplier Workspace ${suffix}`, companyIds: [company] })).id
 const s = await command('/users', { login: supplierLogin, displayName: `Workspace Supplier ${suffix}`, password, roles: ['SUPPLIER'] }), b = await command('/users', { login: buyerLogin, displayName: `Workspace Buyer ${suffix}`, password, roles: ['BUYER'] })
 for (const permission of ['order.read', 'confirmation.submit', 'asn.manage']) await grant(s.id, permission)
 for (const permission of ['order.read', 'delivery.approve']) await grant(b.id, permission)
 await command(`/users/${s.id}/supplier-binding`, { supplierId: supplier }, (await get('/users/' + s.id)).version, 'PUT')
 connection = (await command('/erp-connections', { name: `Workspace Mock ${suffix}`, companyId: company, purchasingOrgId: org, supplierId: supplier, plantIds: [plant] })).id; await sync('CONFIRMATION'); orderId = (await get(`/purchase-orders?companyId=${company}&query=PO-1001`)).items[0].id
 writeFileSync('../private/phase07-test.json', JSON.stringify({ login: adminLogin, password, companyId: company, connectionId: connection, orderId }), { mode: 0o600 })
})
test.afterAll(async () => { await api?.dispose() })
test('supplier workbench and unit statistics use real authorized data', async ({ page }) => {
 await apiLogin(supplierLogin); const d = await get('/dashboard'); expect(d.unconfirmedSchedules).toBeGreaterThan(0)
 await login(page, supplierLogin); await page.getByRole('button', { name: '协同工作台', exact: true }).click(); await expect(page.locator('.metric').filter({ hasText: '待确认计划行' })).toContainText(String(d.unconfirmedSchedules)); await expect(page.getByRole('cell', { name: 'PO-1001', exact: true }).first()).toBeVisible()
 await page.screenshot({ path: '../tmp/phase07-workbench.png', fullPage: true }); await page.getByRole('button', { name: '统计分析', exact: true }).click(); await expect(page.getByRole('cell', { name: `WS-${suffix}`, exact: true }).first()).toBeVisible(); await expect(page.getByRole('cell', { name: 'EA', exact: true }).first()).toBeVisible()
 const stats = await get('/statistics'); expect(stats.every((r: any) => r.companyId === company)).toBe(true); await page.getByRole('button', { name: '刷新统计', exact: true }).click(); await expect(page.getByRole('cell', { name: stats[0].orderedQuantity, exact: true }).first()).toBeVisible(); await page.screenshot({ path: '../tmp/phase07-statistics.png', fullPage: true })
})
test('business event is notified once and own read survives refresh', async ({ page }) => {
 await apiLogin(supplierLogin); const o = await get('/purchase-orders/' + orderId), s = o.items[0].schedules[0]; await command(`/purchase-orders/${orderId}/confirmations`, { scheduleId: s.id, sourceRevision: o.sourceRevision, quantity: '20', promisedDate: s.requestedDate }, o.version)
 await expect.poll(async () => (await get('/notifications?size=100')).items.some((n: any) => n.eventType === 'confirmation.effective'), { timeout: 20000 }).toBe(true)
 noticeId = (await get('/notifications?size=100')).items.find((n: any) => n.eventType === 'confirmation.effective').id
 await login(page, supplierLogin); await page.getByRole('button', { name: '通知中心', exact: true }).click(); const row = page.getByRole('row').filter({ hasText: '订单确认已生效' }); await expect(row).toHaveCount(1); await row.getByRole('button', { name: '标为已读' }).click(); await expect(row.getByRole('cell', { name: '已读', exact: true })).toBeVisible(); await page.reload(); await page.getByRole('button', { name: '通知中心', exact: true }).click(); await expect(page.getByRole('row').filter({ hasText: '订单确认已生效' }).getByRole('cell', { name: '已读', exact: true })).toBeVisible(); expect((await get('/notifications/' + noticeId)).readAt).toBeTruthy(); await page.screenshot({ path: '../tmp/phase07-notifications.png', fullPage: true })
})
test('buyer sees an actionable approval and supplier does not self approve', async ({ page }) => {
 await apiLogin(supplierLogin); const o = await get('/purchase-orders/' + orderId), s = o.items[0].schedules[1], date = new Date(s.requestedDate + 'T00:00:00Z'); date.setUTCDate(date.getUTCDate() + 5)
 await command(`/purchase-orders/${orderId}/confirmations`, { scheduleId: s.id, sourceRevision: o.sourceRevision, quantity: '10', promisedDate: date.toISOString().slice(0, 10), reason: 'Virtual workspace delay' }, o.version); expect((await get('/dashboard')).pendingApprovals).toBe(0)
 await login(page, buyerLogin); await page.getByRole('button', { name: '协同工作台', exact: true }).click(); await expect(page.locator('.metric').filter({ hasText: '待我审批' })).toContainText('1'); const row = page.getByRole('row').filter({ hasText: '审批交期' }); await row.getByRole('button', { name: '进入处理' }).click(); await expect(page.getByRole('heading', { name: '订单协同', exact: true }).first()).toBeVisible()
})
test('sync failure reaches its owner and has visible delivery status', async ({ page }) => {
 await apiLogin(adminLogin); await sync('PERMANENT_FAIL'); await expect.poll(async () => (await get('/notifications?size=100')).items.some((n: any) => n.eventType === 'sync.failed'), { timeout: 20000 }).toBe(true)
 await login(page, adminLogin); await page.getByRole('button', { name: '通知中心', exact: true }).click(); await expect(page.getByRole('cell', { name: '同步任务失败，请查看同步监控', exact: true })).toBeVisible(); await page.getByRole('tab', { name: '投递监控' }).click(); await expect(page.getByRole('row').filter({ hasText: '同步任务失败，请查看同步监控' })).toContainText('已完成'); await page.getByRole('button', { name: '协同工作台', exact: true }).click(); await expect(page.locator('.metric').filter({ hasText: '我的失败同步' })).toContainText('1')
})
