<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type Page } from './api'
const props = defineProps<{ kind: string; permissions: string[] }>()
interface Row { id: string; code: string; name: string; active?: boolean; timezone?: string; companyId?: string; companyIds?: string[]; status?: string; version: number; canEdit?: boolean; companies?: { companyId: string; companyCode: string; companyName: string; active: boolean }[] }
const rows = ref<Row[]>([]), companies = ref<Row[]>([]), page = ref(1), total = ref(0), query = ref(''), companyFilter = ref(''), busy = ref(false), open = ref(false), selected = ref<Row>(), linksOpen = ref(false)
const form = reactive({ code: '', name: '', timezone: 'Asia/Shanghai', active: true, companyId: '', companyIds: [] as string[], status: 'ACTIVE' })
const link = reactive({ companyId: '', active: true })
const supplier = computed(() => props.kind === 'suppliers')
const canWrite = computed(() => props.permissions.includes(supplier.value ? 'supplier.manage' : 'organization.manage'))
const names: Record<string, string> = { companies: '公司', plants: '工厂', 'purchasing-organizations': '采购组织', suppliers: '供应商' }
async function run(fn: () => Promise<void>) { busy.value = true; try { await fn() } catch (e) { ElMessage.error(e instanceof Error ? e.message : '操作失败') } finally { busy.value = false } }
async function load() { const p = new URLSearchParams({ page: String(page.value - 1), size: '20', query: query.value }); if (companyFilter.value && props.kind !== 'companies') p.set('companyId', companyFilter.value); const v = await api<Page<Row>>(`/${props.kind}?${p}`); rows.value = v.items; total.value = v.total }
async function options() { const all: Row[] = []; let p = 0; while (true) { const v = await api<Page<Row>>(`/companies?page=${p++}&size=100`); all.push(...v.items); if (all.length >= v.total || !v.items.length) break } companies.value = all }
function edit(row?: Row) { selected.value = row; Object.assign(form, { code: row?.code || '', name: row?.name || '', timezone: row?.timezone || 'Asia/Shanghai', active: row?.active ?? true, companyId: row?.companyId || '', companyIds: row?.companyIds ? [...row.companyIds] : [], status: row?.status || 'ACTIVE' }); open.value = true }
async function save() { await run(async () => {
  let body: unknown
  if (supplier.value) body = selected.value ? { name: form.name, status: form.status } : { code: form.code, name: form.name, companyIds: form.companyIds }
  else if (props.kind === 'companies') body = { code: form.code, name: form.name, timezone: form.timezone, active: form.active }
  else if (props.kind === 'plants') body = { code: form.code, name: form.name, companyId: form.companyId, active: form.active }
  else body = { code: form.code, name: form.name, companyIds: form.companyIds, active: form.active }
  await api(`/${props.kind}${selected.value ? '/' + selected.value.id : ''}`, selected.value ? 'PATCH' : 'POST', body, selected.value?.version)
  open.value = false; await load(); await options(); ElMessage.success('已保存')
}) }
async function associate() { await run(async () => { const v = await api<Row>(`/suppliers/${selected.value!.id}/companies`, 'POST', link, selected.value!.version); selected.value = v; await load(); ElMessage.success('公司关系已更新') }) }
function companyName(id?: string) { return companies.value.find(c => c.id === id)?.name || id }
watch(() => props.kind, () => { page.value = 1; query.value = ''; companyFilter.value = ''; void run(async () => { await options(); await load() }) })
onMounted(() => run(async () => { await options(); await load() }))
</script>
<template>
  <section class="card table-card" v-loading="busy">
    <div class="master-toolbar"><el-input v-model="query" aria-label="搜索编码或名称" placeholder="搜索编码或名称" clearable @keyup.enter="page = 1; run(load)" style="max-width:260px"/><el-select v-if="kind !== 'companies'" v-model="companyFilter" aria-label="公司筛选" placeholder="全部授权公司" clearable style="width:220px" @change="page = 1; run(load)"><el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id"/></el-select><el-button @click="page = 1; run(load)">查询</el-button><el-button v-if="canWrite" type="primary" @click="edit()">新建{{ names[kind] }}</el-button></div>
    <p v-if="supplier" class="muted">供应商数据按公司授权。共享供应商的名称与状态仅允许拥有全部关联公司管理权限的用户修改。</p>
    <el-table :data="rows" empty-text="暂无授权数据"><el-table-column prop="code" label="编码" min-width="140"/><el-table-column prop="name" label="名称" min-width="180"/>
      <el-table-column v-if="kind === 'companies'" prop="timezone" label="时区" min-width="160"/>
      <el-table-column v-if="kind === 'plants'" label="公司" min-width="160"><template #default="{ row }">{{ companyName(row.companyId) }}</template></el-table-column>
      <el-table-column v-if="kind === 'purchasing-organizations'" label="关联公司" min-width="200"><template #default="{ row }">{{ row.companyIds.map(companyName).join('、') }}</template></el-table-column>
      <el-table-column v-if="supplier" label="授权公司关系" min-width="240"><template #default="{ row }"><el-tag v-for="c in row.companies" :key="c.companyId" :type="c.active ? 'success' : 'info'" class="tag-gap">{{ c.companyName }} · {{ c.active ? '有效' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ supplier ? (row.status === 'ACTIVE' ? '有效' : '冻结') : (row.active ? '有效' : '停用') }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="170"><template #default="{ row }"><el-button link type="primary" :disabled="supplier && !row.canEdit" @click="edit(row)">编辑</el-button><el-button v-if="supplier" link type="primary" @click="selected = row; link.companyId = ''; link.active = true; linksOpen = true">公司关系</el-button></template></el-table-column>
    </el-table><el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="run(load)"/>
  </section>
  <el-drawer v-model="open" :title="(selected ? '编辑' : '新建') + names[kind]" size="480px"><el-form label-position="top" @submit.prevent="save">
    <el-form-item label="编码"><el-input v-model="form.code" :disabled="!!selected" aria-label="业务编码" maxlength="40" placeholder="字母、数字或 . _ -"/></el-form-item><el-form-item label="名称"><el-input v-model="form.name" aria-label="业务名称" maxlength="160"/></el-form-item>
    <el-form-item v-if="kind === 'companies'" label="时区"><el-input v-model="form.timezone" aria-label="时区"/></el-form-item>
    <el-form-item v-if="kind === 'plants'" label="所属公司"><el-select v-model="form.companyId" :disabled="!!selected" aria-label="所属公司"><el-option v-for="c in companies.filter(c => c.active)" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item>
    <el-form-item v-if="kind === 'purchasing-organizations' || (supplier && !selected)" label="关联公司"><el-select v-model="form.companyIds" multiple aria-label="关联公司"><el-option v-for="c in companies.filter(c => c.active)" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item>
    <el-form-item v-if="!supplier" label="有效"><el-switch v-model="form.active" aria-label="有效"/></el-form-item><el-form-item v-else-if="selected" label="状态"><el-select v-model="form.status"><el-option label="有效" value="ACTIVE"/><el-option label="冻结" value="FROZEN"/></el-select></el-form-item>
    <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
  </el-form></el-drawer>
  <el-drawer v-model="linksOpen" title="供应商公司关系" size="480px"><p>{{ selected?.name }}</p><p class="muted">停用关系保留历史。供应商账号将立即失去该公司数据访问权限。</p><el-form label-position="top" @submit.prevent="associate"><el-form-item label="公司"><el-select v-model="link.companyId" aria-label="关系公司"><el-option v-for="c in companies.filter(c => c.active)" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item><el-form-item label="关系有效"><el-switch v-model="link.active"/></el-form-item><el-button type="primary" native-type="submit" :loading="busy">保存公司关系</el-button></el-form></el-drawer>
</template>
<style scoped>.master-toolbar{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:18px}.el-select{width:100%}</style>
