import { test, expect } from '@playwright/test'
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
const securityAccount = { login: 'security-' + randomUUID().slice(0, 12), password: randomUUID() + randomUUID() }
test.beforeAll(async ({ playwright, baseURL }) => {
 const api = await playwright.request.newContext({ baseURL, ignoreHTTPSErrors: true }); let csrf = await (await api.get('/api/v1/auth/csrf')).json(); await api.post('/api/v1/auth/login', { data: JSON.parse(readFileSync('../private/initial-admin.json', 'utf8')), headers: { 'X-CSRF-Token': csrf.csrfToken } }); csrf = await (await api.get('/api/v1/auth/csrf')).json(); const r = await api.post('/api/v1/users', { data: { ...securityAccount, displayName: 'Synthetic Security User', roles: ['USER'] }, headers: { 'X-CSRF-Token': csrf.csrfToken, 'Idempotency-Key': randomUUID() } }); expect(r.status()).toBe(201); await api.dispose()
})
test('cross-origin browser cannot read or change an authenticated profile', async ({ page, baseURL }) => {
 const account = securityAccount
 await page.goto('/'); const csrf = await (await page.request.get('/api/v1/auth/csrf')).json()
 expect((await page.request.post('/api/v1/auth/login', { data: account, headers: { 'X-CSRF-Token': csrf.csrfToken } })).ok()).toBe(true)
 const before = await (await page.request.get('/api/v1/auth/me')).json(); const writeToken = await (await page.request.get('/api/v1/auth/csrf')).json()
 const server = createServer((_request, response) => { response.writeHead(200, { 'Content-Type': 'text/html' }); response.end('<!doctype html><title>Origin isolation fixture</title>') })
 await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
 try {
  const address = server.address() as { port: number }; await page.goto('http://127.0.0.1:' + address.port)
  const blocked = await page.evaluate(async url => { try { await fetch(url + '/api/v1/auth/me', { credentials: 'include' }); return false } catch { return true } }, baseURL!)
  expect(blocked).toBe(true)
  const writeBlocked = await page.evaluate(async ({ url, token, version }) => { try { await fetch(url + '/api/v1/auth/profile', { method: 'PATCH', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': token, 'Idempotency-Key': 'browser-origin-fixture', 'If-Match': '"' + version + '"' }, body: JSON.stringify({ displayName: 'Rejected browser fixture' }) }); return false } catch { return true } }, { url: baseURL!, token: writeToken.csrfToken, version: before.version }); expect(writeBlocked).toBe(true)
  const response = await page.request.get(baseURL! + '/api/v1/auth/me'); expect(await response.json()).toEqual(before)
 } finally { await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())) }
})
test('valid session and CSRF cannot authorize a foreign-origin or wrong-protocol write', async ({ request, baseURL }) => {
 const account = securityAccount
 let csrf = await (await request.get('/api/v1/auth/csrf')).json(); await request.post('/api/v1/auth/login', { data: account, headers: { 'X-CSRF-Token': csrf.csrfToken } })
 const me = await (await request.get('/api/v1/auth/me')).json(); csrf = await (await request.get('/api/v1/auth/csrf')).json()
 const wrongProtocol = new URL(baseURL!); wrongProtocol.protocol = wrongProtocol.protocol === 'https:' ? 'http:' : 'https:'
 for (const origin of ['https://attacker.example.invalid', wrongProtocol.origin]) {
  const r = await request.patch('/api/v1/auth/profile', { data: { displayName: 'Rejected origin fixture' }, headers: { Origin: origin, 'X-Forwarded-Proto': new URL(origin).protocol.slice(0, -1), 'X-CSRF-Token': csrf.csrfToken, 'Idempotency-Key': randomUUID(), 'If-Match': `"${me.version}"` } })
  expect(r.status()).toBe(403); expect((await r.json()).code).toBe('CROSS_ORIGIN_WRITE_DENIED'); expect(await (await request.get('/api/v1/auth/me')).json()).toEqual(me)
 }
})
test('response protections and private-file isolation apply at the web edge', async ({ request, baseURL }) => {
 const r = await request.get('/'); expect(r.status()).toBe(200); expect(r.headers()['content-security-policy']).toContain("frame-ancestors 'none'"); expect(r.headers()['x-content-type-options']).toBe('nosniff'); expect(r.headers()['cache-control']).toContain('no-store'); expect(r.headers()['server']).not.toMatch(/nginx\//)
 if (baseURL?.startsWith('https:')) expect(r.headers()['strict-transport-security']).toContain('max-age=86400')
 for (const path of ['/private/idempotency-key', '/.env', '/actuator/env']) { const response = await request.get(path); expect(response.status()).toBe(404); expect(await response.text()).not.toContain('OPEN_SRM_DB_USER=') }
})
