export interface User { id: string; login: string; displayName: string; status: 'ACTIVE' | 'DISABLED'; roles: string[]; version: number }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Audit { id: string; actorId: string; operation: string; objectId: string; requestId: string; createdAt: string }
const messages: Record<string, string> = {
  SHIPPING_QUANTITY_EXCEEDED: '送货数量超过计划额度或可发余额', PLAN_ALREADY_SHIPPED: '计划已有发货，不能整量改期', ASN_HAS_RECEIPTS: 'ASN 已有关联收货，不能取消', ASN_NOT_DRAFT: 'ASN 已提交或失效，请刷新', ASN_REFERENCE_EXISTS: '送货通知号已存在', PLAN_NOT_ACTIVE: '交货计划已失效', ASN_SCOPE_MISMATCH: '所有行必须属于同一公司和供应商', DUPLICATE_PLAN: '交货计划不能重复',
  QUANTITY_EXCEEDED: '确认数量超过可用余额（含待审批预占）', INVALID_QUANTITY: '数量必须大于零且符合精度要求', DELAY_REASON_REQUIRED: '延期确认必须填写原因',
  SOURCE_REVISION_CONFLICT: '订单来源版本已变化，请刷新后重新处理', ORDER_NOT_ACTIVE: '订单已取消或关闭', CONSISTENCY_HOLD: '订单需要一致性核对，暂不能提交', SUPPLIER_FROZEN: '供应商已冻结，当前仅可查看历史',
  SELF_APPROVAL_FORBIDDEN: '申请人不能审批自己的申请', CHANGE_ALREADY_PENDING: '该确认已有待审批变更', CHANGE_NOT_PENDING: '申请已结束或因版本变更失效', CONFIRMATION_NOT_EFFECTIVE: '仅可变更已生效确认', DATE_UNCHANGED: '新交期必须与原承诺不同', REQUESTER_REQUIRED: '仅申请人可以撤回', CONFIRMATION_VERSION_CONFLICT: '确认版本已变化，请刷新', CONFIRMATION_STATE_CONFLICT: '确认状态已变化', SUPPLIER_IDENTITY_REQUIRED: '提交需要正确绑定的供应商账号',
  SYNC_ALREADY_RUNNING: '该连接已有进行中的任务', RETRY_NOT_ALLOWED: '该任务状态或重试次数不允许继续', CONNECTION_DISABLED: '连接已停用', DUPLICATE_PLANT: '工厂映射不可重复',
  CODE_UNAVAILABLE: '编码已存在', CODE_IMMUTABLE: '编码不可修改', GRANT_ALREADY_EXISTS: '该授权已存在',
  ROLE_COMBINATION_NOT_ALLOWED: '供应商角色不可与其他角色组合', SUPPLIER_ROLE_REQUIRED: '请先设置为独立供应商角色', BINDING_MUST_BE_REMOVED: '请先移除供应商绑定',
  IMMUTABLE_ORGANIZATION_PARENT: '工厂所属公司不可修改', DUPLICATE_COMPANY: '关联公司不可重复',
  INVALID_CREDENTIALS: '账号或密码不正确', LOGIN_THROTTLED: '尝试次数过多，请 5 分钟后重试',
  UNAUTHENTICATED: '会话已失效，请重新登录', FORBIDDEN_OR_CSRF: '没有操作权限或验证已过期，请刷新重试',
  FORBIDDEN: '没有此操作权限', VERSION_CONFLICT: '记录已更新，请刷新后重试', LOGIN_UNAVAILABLE: '该账号已被使用',
  SELF_DISABLE_FORBIDDEN: '不能禁用当前登录账号', LAST_ADMIN_REQUIRED: '至少需要保留一位有效管理员',
  NOT_FOUND: '记录不存在、超出授权范围或初始化入口已关闭', VALIDATION_FAILED: '请检查字段格式与长度',
  PASSWORD_TOO_LONG: '密码的 UTF-8 长度不能超过 72 字节', IDEMPOTENCY_CONFLICT: '请求编号已用于其他内容',
}
export class ApiError extends Error { constructor(public status: number, public code: string, public requestId?: string) { super(messages[code] || '操作未完成，请稍后重试') } }
export async function api<T>(path: string, method = 'GET', body?: unknown, version?: number): Promise<T> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const result = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
    if (!result.ok) throw new Error('无法获取安全验证，请检查服务连接')
    const value = await result.json() as { csrfToken: string }
    headers['X-CSRF-Token'] = value.csrfToken
    headers['Idempotency-Key'] = crypto.randomUUID()
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (version !== undefined) headers['If-Match'] = `"${version}"`
  const response = await fetch(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.code || 'UNKNOWN', problem.requestId)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
