<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import CollaborationDesk from './CollaborationDesk.vue'
import ShippingDesk from './ShippingDesk.vue'
import WorkspaceDesk from './WorkspaceDesk.vue'
import NotificationCenter from './NotificationCenter.vue'
import PurchaseOrders from './PurchaseOrders.vue'
import SyncMonitor from './SyncMonitor.vue'
import MasterData from './MasterData.vue'
import UserAccess from './UserAccess.vue'
import { api, ApiError, type User, type Page, type Audit } from './api'
const me = ref<User>(), ready = ref(false), setup = ref(false), busy = ref(false)
const tab = ref('account'), page = ref(1), total = ref(0), users = ref<User[]>([]), audits = ref<Audit[]>([])
const login = reactive({ login: '', password: '' })
const bootstrap = reactive({ bootstrapToken: '', login: '', displayName: '', password: '' })
const profile = ref(''), createOpen = ref(false), roleOpen = ref(false), selected = ref<User>(), selectedRoles = ref<string[]>([])
const newUser = reactive({ login: '', displayName: '', password: '', roles: ['USER'] })
const access = ref<{ permissions: string[] }>({ permissions: [] }), accessOpen = ref(false)
async function refreshAccess() { access.value = await api('/auth/access') }
const canManage = computed(() => me.value?.roles.includes('ADMIN'))
const canAudit = computed(() => me.value?.roles.includes('AUDITOR'))
const roleNames: Record<string, string> = { ADMIN: '系统管理员', AUDITOR: '审计员', USER: '普通用户', BUYER: '采购员', SUPPLIER: '供应商用户' }
const titles: Record<string, string> = { dashboard: '协同工作台', statistics: '统计分析', notifications: '通知中心', account: '我的工作空间', users: '用户与角色', audit: '操作审计', companies: '公司管理', plants: '工厂管理', 'purchasing-organizations': '采购组织', suppliers: '供应商管理', orders: '采购订单', sync: 'ERP 同步监控', collaboration: '订单协同', shipping: '送货与实际收货' }
function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 401 && me.value) { me.value = undefined; login.password = '' }
  ElMessage.error(error instanceof Error ? error.message : '服务连接异常')
}
async function run(action: () => Promise<void>) {
  busy.value = true
  try { await action() } catch (error) { errorMessage(error) } finally { busy.value = false }
}
async function load() {
  if (tab.value === 'users') { const value = await api<Page<User>>(`/users?page=${page.value - 1}&size=20`); users.value = value.items; total.value = value.total }
  if (tab.value === 'audit') { const value = await api<Page<Audit>>(`/audit-events?page=${page.value - 1}&size=20`); audits.value = value.items; total.value = value.total }
}
async function navigate(value: string) { tab.value = value; page.value = 1; await run(load) }
async function signIn() { await run(async () => { me.value = await api<User>('/auth/login', 'POST', login); login.password = ''; profile.value = me.value.displayName; await refreshAccess(); tab.value = 'account' }) }
async function initialize() { await run(async () => { await api('/auth/bootstrap', 'POST', bootstrap); bootstrap.password = ''; bootstrap.bootstrapToken = ''; setup.value = false; ElMessage.success('管理员已创建，请登录') }) }
async function signOut() { await run(async () => { await api('/auth/logout', 'POST'); me.value = undefined; tab.value = 'account' }) }
async function saveProfile() { await run(async () => { me.value = await api<User>('/auth/profile', 'PATCH', { displayName: profile.value }, me.value!.version); ElMessage.success('个人资料已保存') }) }
async function createUser() { await run(async () => { await api('/users', 'POST', newUser); newUser.password = ''; createOpen.value = false; await load(); ElMessage.success('用户已创建') }) }
async function changeStatus(user: User) { await run(async () => { await api(`/users/${user.id}`, 'PATCH', { displayName: user.displayName, status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }, user.version); await load(); ElMessage.success('账号状态已更新') }) }
function editRoles(user: User) { selected.value = user; selectedRoles.value = [...user.roles]; roleOpen.value = true }
async function saveRoles() { await run(async () => {
  await api(`/users/${selected.value!.id}/roles`, 'PUT', { roles: selectedRoles.value }, selected.value!.version)
  roleOpen.value = false
  if (selected.value?.id === me.value?.id) { me.value = undefined; ElMessage.info('角色已更新，请重新登录') } else { await load(); ElMessage.success('角色已更新，旧会话将失效') }
}) }
onMounted(async () => {
  try {
    setup.value = (await api<{ setupRequired: boolean }>('/auth/bootstrap/status')).setupRequired
    try { me.value = await api<User>('/auth/me'); profile.value = me.value.displayName; await refreshAccess() } catch (error) { if (!(error instanceof ApiError && error.status === 401)) throw error }
  } catch (error) { errorMessage(error) } finally { ready.value = true }
})
</script>

<template>
  <div v-if="!ready" class="loading-screen">正在连接工作空间…</div>
  <main v-else-if="!me" class="auth-layout">
    <section class="auth-story"><div class="brand"><span class="brand-icon">O</span> OpenSRM</div><div class="story-copy"><p class="eyebrow">SUPPLIER COLLABORATION</p><h1>让每一次协同，<br>都有清晰的起点。</h1><p>连接采购与供应商，建立透明、可信的协同工作空间。</p><div class="story-line"></div><span>开放架构 · 独立部署 · 权限可控</span></div><small>OpenSRM / 协同工作空间</small></section>
    <section class="auth-panel"><div class="auth-card"><span class="label">{{ setup ? '首次使用' : '欢迎回来' }}</span><h2>{{ setup ? '初始化工作空间' : '登录 OpenSRM' }}</h2><p class="muted">{{ setup ? '使用部署时生成的初始化令牌，创建第一位管理员。' : '输入你的账号与密码，开始本次工作。' }}</p>
      <el-form v-if="setup" label-position="top" @submit.prevent="initialize">
        <el-form-item label="初始化令牌"><el-input v-model="bootstrap.bootstrapToken" type="password" autocomplete="off" show-password aria-label="初始化令牌" /></el-form-item>
        <el-form-item label="管理员账号"><el-input v-model="bootstrap.login" autocomplete="username" aria-label="管理员账号" placeholder="至少 3 位小写字母、数字或 . _ -" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="bootstrap.displayName" maxlength="120" aria-label="显示名称" /></el-form-item>
        <el-form-item label="管理员密码"><el-input v-model="bootstrap.password" type="password" show-password autocomplete="new-password" aria-label="管理员密码" placeholder="至少 12 个字符，最多 72 字节" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" class="wide">创建管理员</el-button>
      </el-form>
      <el-form v-else label-position="top" @submit.prevent="signIn">
        <el-form-item label="账号"><el-input v-model="login.login" autocomplete="username" aria-label="账号" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="login.password" type="password" show-password autocomplete="current-password" aria-label="密码" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" class="wide">登录工作空间</el-button>
      </el-form><p class="auth-note">访问权限由管理员授予。请妥善保管账号信息。</p>
    </div></section>
  </main>
  <div v-else class="workspace">
    <aside class="sidebar"><div class="brand"><span class="brand-icon">O</span> OpenSRM</div><p class="nav-caption">工作空间</p><nav>
      <button :class="{ active: tab === 'dashboard' }" @click="navigate('dashboard')">协同工作台</button>
      <button :class="{ active: tab === 'notifications' }" @click="navigate('notifications')">通知中心</button>
      <button v-if="access.permissions.includes('order.read')" :class="{ active: tab === 'statistics' }" @click="navigate('statistics')">统计分析</button>
      <button :class="{ active: tab === 'account' }" @click="navigate('account')"><span aria-hidden="true">◈</span> 我的账号</button>
      <button v-if="canManage" :class="{ active: tab === 'users' }" @click="navigate('users')"><span aria-hidden="true">▤</span> 用户与角色</button>
      <template v-if="access.permissions.includes('organization.read')"><button v-for="key in ['companies', 'plants', 'purchasing-organizations']" :key="key" :class="{ active: tab === key }" @click="navigate(key)"><span aria-hidden="true">▦</span> {{ titles[key] }}</button></template>
      <button v-if="access.permissions.includes('supplier.read')" :class="{ active: tab === 'suppliers' }" @click="navigate('suppliers')"><span aria-hidden="true">◇</span> 供应商管理</button>
      <button v-if="access.permissions.includes('order.read')" :class="{ active: tab === 'orders' }" @click="navigate('orders')">采购订单</button>
      <button v-if="access.permissions.includes('order.read')" :class="{ active: tab === 'collaboration' }" @click="navigate('collaboration')">订单协同</button>
      <button v-if="access.permissions.includes('order.read')" :class="{ active: tab === 'shipping' }" @click="navigate('shipping')">送货与实际收货</button>
      <button v-if="access.permissions.includes('sync.read')" :class="{ active: tab === 'sync' }" @click="navigate('sync')">ERP 同步监控</button>
      <button v-if="canAudit" :class="{ active: tab === 'audit' }" @click="navigate('audit')"><span aria-hidden="true">◎</span> 操作审计</button>
    </nav><div class="sidebar-foot"><span class="live-dot"></span> 安全会话已连接<p>权限随账号授权实时生效</p></div></aside>
    <div class="main-panel"><header><span class="breadcrumb">工作空间 <b>/</b> {{ titles[tab] }}</span><div class="header-user"><span class="avatar">{{ me.displayName.slice(0, 1) }}</span>{{ me.displayName }}<el-button text @click="signOut" :loading="busy">退出登录</el-button></div></header>
      <main class="content"><div class="page-heading"><div><p class="eyebrow">{{ tab === 'account' ? 'ACCOUNT OVERVIEW' : tab === 'users' ? 'ACCESS MANAGEMENT' : 'ACTIVITY LOG' }}</p><h1>{{ titles[tab] }}</h1><p class="muted">{{ tab === 'account' ? '管理你的个人资料，了解当前访问权限。' : tab === 'users' ? '统一管理账号状态与角色，保持访问权限清晰。' : ['dashboard', 'statistics', 'notifications'].includes(tab) ? '查看当前权限范围内的协同事项和数据。' : '查看工作空间内的身份与权限操作记录。' }}</p></div><el-button v-if="tab === 'users'" type="primary" @click="createOpen = true">＋ 新建用户</el-button></div>
      <template v-if="tab === 'account'"><div class="account-banner"><div><span class="label">当前登录</span><h2>{{ me.displayName }}，你好</h2><p>你的账号已通过身份验证。</p></div><el-tag type="success" effect="light">账号有效</el-tag></div><div class="account-grid"><section class="card"><div class="section-title"><h3>个人资料</h3><span>PROFILE</span></div><el-form label-position="top" @submit.prevent="saveProfile"><el-form-item label="登录账号"><el-input :model-value="me.login" disabled /></el-form-item><el-form-item label="显示名称"><el-input v-model="profile" maxlength="120" aria-label="个人显示名称" /></el-form-item><el-button type="primary" native-type="submit" :loading="busy">保存资料</el-button></el-form></section><section class="card"><div class="section-title"><h3>访问权限</h3><span>ACCESS</span></div><p class="muted">当前分配的角色</p><div class="role-tags"><el-tag v-for="role in me.roles" :key="role" effect="plain">{{ roleNames[role] || role }}</el-tag></div><div class="permission-note">角色决定可执行的操作。业务数据访问还需对应的组织授权。</div></section></div></template>
      <section v-else-if="tab === 'users'" class="card table-card"><div class="table-summary">账号列表 <span>{{ total }} 个账号</span><el-button text :loading="busy" @click="run(load)">刷新</el-button></div><el-table :data="users" stripe empty-text="暂无用户"><el-table-column prop="login" label="账号" min-width="140"/><el-table-column prop="displayName" label="显示名称" min-width="150"/><el-table-column label="角色" min-width="220"><template #default="{ row }"><el-tag v-for="role in row.roles" :key="role" class="tag-gap" size="small" effect="plain">{{ roleNames[role] || role }}</el-tag></template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status === 'ACTIVE' ? '有效' : '禁用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="230"><template #default="{ row }"><el-button link type="primary" @click="selected = row; accessOpen = true">数据授权</el-button><el-button link type="primary" :disabled="busy" @click="editRoles(row)">角色</el-button><el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'primary'" :disabled="busy || row.id === me.id" @click="changeStatus(row)">{{ row.status === 'ACTIVE' ? '禁用' : '启用' }}</el-button></template></el-table-column></el-table></section>
      <WorkspaceDesk v-else-if="tab === 'dashboard'" :permissions="access.permissions" @navigate="navigate"/><WorkspaceDesk v-else-if="tab === 'statistics'" statistics :permissions="access.permissions"/><NotificationCenter v-else-if="tab === 'notifications'" :manager="!!canManage" @navigate="navigate"/><ShippingDesk v-else-if="tab === 'shipping'" :permissions="access.permissions"/><PurchaseOrders v-else-if="tab === 'orders'" :permissions="access.permissions"/><CollaborationDesk v-else-if="tab === 'collaboration'" :permissions="access.permissions" :user-id="me.id"/><SyncMonitor v-else-if="tab === 'sync'"/>
      <MasterData v-else-if="['companies', 'plants', 'purchasing-organizations', 'suppliers'].includes(tab)" :kind="tab" :permissions="access.permissions"/>
      <section v-else class="card table-card"><div class="table-summary">操作记录 <span>{{ total }} 条记录</span><el-button text :loading="busy" @click="run(load)">刷新</el-button></div><el-table :data="audits" empty-text="暂无记录"><el-table-column label="时间" width="190"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template></el-table-column><el-table-column prop="operation" label="操作" min-width="200"/><el-table-column prop="actorId" label="操作人 ID" min-width="280"/><el-table-column prop="requestId" label="追踪编号" min-width="280"/></el-table></section>
      <el-pagination v-if="['users', 'audit'].includes(tab)" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="run(load)" />
      <footer>OpenSRM <span>身份与访问管理</span></footer></main>
    </div>
    <el-drawer v-model="createOpen" title="新建用户" size="420px" @closed="newUser.password = ''"><el-form label-position="top" @submit.prevent="createUser"><el-form-item label="登录账号"><el-input v-model="newUser.login" aria-label="新用户账号" placeholder="小写字母、数字或 . _ -，至少 3 位" /></el-form-item><el-form-item label="显示名称"><el-input v-model="newUser.displayName" aria-label="新用户名称" /></el-form-item><el-form-item label="初始密码"><el-input v-model="newUser.password" aria-label="新用户密码" type="password" show-password autocomplete="new-password" placeholder="至少 12 个字符，最多 72 字节" /></el-form-item><el-form-item label="角色"><el-checkbox-group v-model="newUser.roles"><el-checkbox v-for="(name, role) in roleNames" :key="role" :value="role">{{ name }}</el-checkbox></el-checkbox-group></el-form-item><el-button type="primary" native-type="submit" :loading="busy">创建用户</el-button></el-form></el-drawer>
    <el-drawer v-model="accessOpen" title="组织与供应商授权" size="650px" destroy-on-close @closed="run(async () => { await load(); await refreshAccess() })"><UserAccess v-if="selected && accessOpen" :user="selected" @changed="refreshAccess"/></el-drawer>
    <el-dialog v-model="roleOpen" title="调整角色" width="420px"><p class="muted">{{ selected?.displayName }} · 角色变更后，该账号已有会话将失效。</p><el-checkbox-group v-model="selectedRoles"><el-checkbox v-for="(name, role) in roleNames" :key="role" :value="role">{{ name }}</el-checkbox></el-checkbox-group><template #footer><el-button @click="roleOpen = false">取消</el-button><el-button type="primary" :loading="busy" @click="saveRoles">保存角色</el-button></template></el-dialog>
  </div>
</template>
