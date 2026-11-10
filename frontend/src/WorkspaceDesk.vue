<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from './api'
const props = defineProps<{ statistics?: boolean; permissions: string[] }>()
const emit = defineEmits<{ navigate: [target: string] }>()
interface Task { id: string; orderId: string; externalOrder: string; kind: string; dueDate: string }
interface Dashboard { orders: number; heldOrders: number; unconfirmedSchedules: number; pendingApprovals: number; overduePlans: number; failedSyncJobs: number; unreadNotifications: number; tasks: Task[] }
interface Statistic { companyId: string; companyCode: string; plantId: string; plantCode: string; unit: string; orderedQuantity: string; effectiveQuantity: string; pendingQuantity: string; submittedQuantity: string; netReceiptQuantity: string; inTransitQuantity: string }
const data = ref<Dashboard>(), rows = ref<Statistic[]>([]), allRows = ref<Statistic[]>([]), busy = ref(false), company = ref(''), plant = ref('')
const companies = computed(() => [...new Map(allRows.value.map(r => [r.companyId, r.companyCode])).entries()])
const plants = computed(() => [...new Map(allRows.value.filter(r => !company.value || r.companyId === company.value).map(r => [r.plantId, r.plantCode])).entries()])
const cards: [keyof Omit<Dashboard, 'tasks'>, string, string][] = [['orders', '可见订单', 'orders'], ['unconfirmedSchedules', '待确认计划行', 'collaboration'], ['pendingApprovals', '待我审批', 'collaboration'], ['overduePlans', '逾期未发完计划', 'shipping'], ['heldOrders', '一致性冻结订单', 'orders'], ['failedSyncJobs', '我的失败同步', 'sync'], ['unreadNotifications', '未读站内通知', 'notifications']]
const kinds: Record<string, string> = { CONFIRM: '确认订单', APPROVE: '审批交期', OVERDUE: '跟进送货' }
async function load() {
 busy.value = true
 try {
  if (props.statistics) { const p = new URLSearchParams(); if (company.value) p.set('companyId', company.value); if (plant.value) p.set('plantId', plant.value); rows.value = await api<Statistic[]>('/statistics?' + p); if (!company.value && !plant.value) allRows.value = rows.value }
  else data.value = await api<Dashboard>('/dashboard')
 } catch (e) { rows.value = []; data.value = undefined; ElMessage.error(e instanceof Error ? e.message : '加载失败') } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
 <section class="card" v-if="props.statistics">
  <h2>组织内数量统计</h2><p class="muted">按当前可见公司、工厂及单位分别汇总。ASN 不等于实际收货；未分配收货不抵减其他 ASN 的在途。</p>
  <el-select v-model="company" clearable aria-label="统计公司" placeholder="全部可见公司" @change="plant = ''; load()"><el-option v-for="[id, code] in companies" :key="id" :value="id" :label="code"/></el-select>
  <el-select v-model="plant" clearable aria-label="统计工厂" placeholder="全部可见工厂" @change="load"><el-option v-for="[id, code] in plants" :key="id" :value="id" :label="code"/></el-select>
  <el-button :loading="busy" :disabled="busy" @click="load">刷新统计</el-button>
  <el-table :data="rows" empty-text="当前权限范围内暂无统计数据"><el-table-column prop="companyCode" label="公司"/><el-table-column prop="plantCode" label="工厂"/><el-table-column prop="unit" label="单位"/><el-table-column prop="orderedQuantity" label="当前订购量"/><el-table-column prop="effectiveQuantity" label="生效确认量"/><el-table-column prop="pendingQuantity" label="待批确认量"/><el-table-column prop="submittedQuantity" label="有效 ASN 量"/><el-table-column prop="netReceiptQuantity" label="ERP 净收货"/><el-table-column prop="inTransitQuantity" label="ASN 在途"/></el-table>
  <p class="muted">订购与确认按当前来源版本；ASN、收货及在途包含保留的历史版本。不同单位不合计。ERP 净收货＝收货－冲销；在途按每条有效 ASN 行扣除其净收货分配。</p>
 </section>
 <template v-else>
  <el-button :loading="busy" :disabled="busy" @click="load">刷新工作台</el-button>
  <div v-if="data" class="workspace-metrics"><button v-for="[key, label, target] in cards.filter(c => c[0] === 'unreadNotifications' || (c[0] === 'failedSyncJobs' ? props.permissions.includes('sync.read') : props.permissions.includes('order.read')))" :key="key" class="card metric" @click="emit('navigate', target)"><span>{{ label }}</span><strong>{{ data[key] }}</strong></button></div>
  <section class="card"><h2>我的待办</h2><p class="muted">只显示当前权限内、来源版本有效的事项。每类最多展示 10 项；逾期按公司时区判断。</p><el-table :data="data?.tasks || []" empty-text="当前没有待处理事项"><el-table-column prop="externalOrder" label="采购订单"/><el-table-column label="事项"><template #default="{ row }">{{ kinds[row.kind] }}</template></el-table-column><el-table-column prop="dueDate" label="相关日期"/><el-table-column label="操作"><template #default="{ row }"><el-button @click="emit('navigate', row.kind === 'OVERDUE' ? 'shipping' : 'collaboration')">进入处理</el-button></template></el-table-column></el-table></section>
 </template>
</template>
<style scoped>
.workspace-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:16px;margin:18px 0}.metric{text-align:left;cursor:pointer;font:inherit;color:inherit}.metric strong{display:block;font-size:30px;margin-top:12px}.el-select{width:210px;margin:0 12px 16px 0}
</style>
