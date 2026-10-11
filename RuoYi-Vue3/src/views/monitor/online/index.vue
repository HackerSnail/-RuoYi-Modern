<template>
   <div class="app-container">
      <el-form :model="queryParams" ref="queryRef" :inline="true">
         <el-form-item label="登录地址" prop="ipaddr">
            <el-input
               v-model="queryParams.ipaddr"
               placeholder="请输入登录地址"
               clearable
               style="width: 200px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item label="用户名称" prop="userName">
            <el-input
               v-model="queryParams.userName"
               placeholder="请输入用户名称"
               clearable
               style="width: 200px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
         </el-form-item>
      </el-form>
      <el-table
         v-loading="loading"
         :data="onlineList.slice((pageNum - 1) * pageSize, pageNum * pageSize)"
         style="width: 100%;"
      >
         <el-table-column label="序号" width="50" type="index" align="center">
            <template #default="scope">
               <span>{{ (pageNum - 1) * pageSize + scope.$index + 1 }}</span>
            </template>
         </el-table-column>
         <el-table-column align="center" prop="tokenId" :show-overflow-tooltip="true">
            <template #header>
               <el-tooltip placement="top" effect="light">
                  <template #content>
                     服务端由会话 uuid 单向派生的脱敏标识，只用于回传定位强退目标；<br />它不是登录凭证，拿到它也仿不出可通过验签的令牌。
                  </template>
                  <span>会话标识</span>
               </el-tooltip>
            </template>
         </el-table-column>
         <el-table-column label="登录名称" align="center" prop="userName" :show-overflow-tooltip="true" />
         <el-table-column label="所属部门" align="center" prop="deptName" :show-overflow-tooltip="true" />
         <el-table-column label="主机" align="center" prop="ipaddr" :show-overflow-tooltip="true" />
         <el-table-column label="登录地点" align="center" prop="loginLocation" :show-overflow-tooltip="true" />
         <el-table-column label="操作系统" align="center" prop="os" :show-overflow-tooltip="true" />
         <el-table-column label="浏览器" align="center" prop="browser" :show-overflow-tooltip="true" />
         <el-table-column align="center" prop="loginTime" width="180">
            <template #header>
               <el-tooltip placement="top" effect="light">
                  <template #content>
                     登录那一刻的快照，会话存续期间不刷新；<br />主机、登录地点、操作系统、浏览器同为快照口径。列表已按登录时间倒序。
                  </template>
                  <span>登录时间</span>
               </el-tooltip>
            </template>
            <template #default="scope">
               <span>{{ parseTime(scope.row.loginTime) }}</span>
            </template>
         </el-table-column>
         <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
            <template #default="scope">
               <el-button link type="primary" icon="Delete" :disabled="!scope.row.tokenId" @click="handleForceLogout(scope.row)" v-hasPermi="['monitor:online:forceLogout']">强退</el-button>
            </template>
         </el-table-column>
      </el-table>

      <pagination v-show="total > 0" :total="total" v-model:page="pageNum" v-model:limit="pageSize" />
   </div>
</template>

<script setup name="Online">
import { forceLogout, list } from "@/api/monitor/online"

const { proxy } = getCurrentInstance()

const onlineList = ref([])
const loading = ref(true)
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const queryParams = ref({
  ipaddr: undefined,
  userName: undefined
})

/** 查询在线用户列表（一次拉全量，分页靠表格上方的前端切片） */
function getList() {
  loading.value = true
  list(queryParams.value).then(response => {
    onlineList.value = response.rows
    total.value = response.total
  }).catch(() => {
    // 失败提示已由请求拦截器统一给出，这里不重复弹
  }).finally(() => {
    // 必须收口：一旦请求被 reject 就无处置回，表格会永久停在 loading 转圈
    loading.value = false
  })
}

/** 搜索按钮操作 */
function handleQuery() {
  pageNum.value = 1
  getList()
}

/** 重置按钮操作 */
function resetQuery() {
  proxy.resetForm("queryRef")
  handleQuery()
}

/** 强退按钮操作 */
function handleForceLogout(row) {
  proxy.$modal.confirm('是否确认强退名称为"' + row.userName + '"的用户?').then(function () {
    // 必须把内层 promise 回传出去：不 return 则外层 then 立即完成，强退结果与重拉列表会在确认框之后才到，
    // 调用链上的异常也回不到最下面的 .catch
    return forceLogout(row.tokenId).then(() => {
      proxy.$modal.msgSuccess("强退成功")
    }).catch(() => {
      // 失败文案（会话标识不合法 / 操作失败）已由请求拦截器给出，不在此重复弹
    }).finally(() => {
      // 无论成败都重拉：强退失败最常见的原因是会话已过期或用户重新登录导致标识失配，
      // 此时表格里的这一行本身已经脏了
      getList()
    })
  }).catch(() => {})
}

getList()
</script>
