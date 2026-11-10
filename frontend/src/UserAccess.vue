<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type Page, type User } from './api'
const props = defineProps<{ user: User }>(), emit = defineEmits<{ changed: [] }>()
interface Item { id: string; code: string; name: string; companyId?: string; companyIds?: string[] }
interface Grant { id: string; permission: string; companyId: string; plantId?: string; purchasingOrgId?: string }
const target = ref(props.user), grants = ref<Grant[]>([]), companies = ref<Item[]>([]), plants = ref<Item[]>([]), orgs = ref<Item[]>([]), suppliers = ref<Item[]>([]), binding = ref<string | null>(null), busy = ref(false)
const form = reactive({ permission: 'organization.read', companyId: '', plantId: '', purchasingOrgId: '' })
async function all(path: string) { const rows: Item[] = []; let page = 0; while (true) { const p = await api<Page<Item>>(`${path}?size=100&page=${page++}`); rows.push(...p.items); if (!p.items.length || rows.length >= p.total) break } return rows }
async function load() { target.value = await api<User>(`/users/${props.user.id}`); grants.value = await api<Grant[]>(`/users/${props.user.id}/grants`); binding.value = (await api<{ supplierId: string | null }>(`/users/${props.user.id}/supplier-binding`)).supplierId }
async function run(fn: () => Promise<void>) { busy.value = true; try { await fn() } catch (e) { ElMessage.error(e instanceof Error ? e.message : '操作失败') } finally { busy.value = false } }
async function add() { await run(async () => { await api(`/users/${target.value.id}/grants`, 'POST', { ...form, plantId: form.plantId || null, purchasingOrgId: form.purchasingOrgId || null }, target.value.version); await load(); emit('changed'); ElMessage.success('授权已保存') }) }
async function revoke(g: Grant) { await run(async () => { await api(`/users/${target.value.id}/grants/${g.id}`, 'DELETE', undefined, target.value.version); await load(); emit('changed'); ElMessage.success('授权已撤销') }) }
async function bind() { await run(async () => { await api(`/users/${target.value.id}/supplier-binding`, 'PUT', { supplierId: binding.value || null }, target.value.version); await load(); emit('changed'); ElMessage.success('绑定已更新，该账号旧会话已失效') }) }
const permissionNames: Record<string, string> = { 'organization.read': '组织目录查看', 'supplier.read': '供应商查看', 'supplier.manage': '供应商管理', 'audit.read': '业务审计查看', 'order.read': '采购订单查看', 'sync.manage': '公司同步管理', 'confirmation.submit': '供应商确认提交', 'delivery.approve': '采购交期审批', 'asn.manage': 'ASN 送货管理' }
function name(id: string, items: Item[]) { return items.find(i => i.id === id)?.name || id }
onMounted(() => run(async () => { await load(); [companies.value, plants.value, orgs.value, suppliers.value] = await Promise.all([all('/companies'), all('/plants'), all('/purchasing-organizations'), all('/suppliers')]) }))
</script>
<template><div v-loading="busy"><h3>{{ target.displayName }}</h3><p class="muted">角色决定操作，授权决定数据。工厂和采购组织限制属于同一条授权；空值表示公司内全部。供应商查看和管理需要公司级授权。</p>
  <el-form label-position="top" @submit.prevent="add"><el-form-item label="权限"><el-select v-model="form.permission"><el-option v-for="(label, key) in permissionNames" :key="key" :label="label" :value="key"/></el-select></el-form-item>
  <el-form-item label="授权公司"><el-select v-model="form.companyId" aria-label="授权公司" @change="form.plantId = ''; form.purchasingOrgId = ''"><el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item>
  <el-form-item label="限制工厂（可选）"><el-select v-model="form.plantId" clearable><el-option v-for="p in plants.filter(p => p.companyId === form.companyId)" :key="p.id" :label="p.name" :value="p.id"/></el-select></el-form-item>
  <el-form-item label="限制采购组织（可选）"><el-select v-model="form.purchasingOrgId" clearable><el-option v-for="o in orgs.filter(o => o.companyIds?.includes(form.companyId))" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item><el-button type="primary" native-type="submit" :loading="busy">添加授权</el-button></el-form>
  <el-table :data="grants" empty-text="尚无组织授权"><el-table-column label="权限"><template #default="{ row }">{{ permissionNames[row.permission] }}</template></el-table-column><el-table-column label="范围"><template #default="{ row }">{{ name(row.companyId, companies) }} / {{ row.plantId ? name(row.plantId, plants) : '全部工厂' }} / {{ row.purchasingOrgId ? name(row.purchasingOrgId, orgs) : '全部采购组织' }}</template></el-table-column><el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table>
  <template v-if="target.roles.includes('SUPPLIER')"><h3>供应商账号绑定</h3><p class="muted">只允许绑定一家供应商。绑定与公司授权必须同时满足。</p><el-select v-model="binding" clearable placeholder="选择供应商"><el-option v-for="s in suppliers" :key="s.id" :label="s.code + ' · ' + s.name" :value="s.id"/></el-select><el-button @click="bind" :loading="busy">保存绑定</el-button></template>
</div></template>
<style scoped>.el-select{width:100%}</style>
