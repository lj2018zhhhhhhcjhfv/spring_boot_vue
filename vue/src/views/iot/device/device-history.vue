<template>
  <div style="padding-left: 20px">
    <el-form :inline="true" label-width="80px" style="margin-bottom: 10px">
      <el-form-item label="历史属性">
        <el-select
          v-model="queryParams.identity"
          placeholder="请选择历史属性"
          clearable
          filterable
          size="small"
          style="width: 220px"
          @change="onIdentityChange"
        >
          <el-option
            v-for="item in historyOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          size="small"
          style="width: 320px"
          value-format="yyyy-MM-dd HH:mm:ss"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="['00:00:00', '23:59:59']"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">查询</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>
    <el-alert
      v-if="!historyOptions.length"
      type="info"
      :closable="false"
      title="当前设备暂无开启历史存储的属性。"
      show-icon
      style="margin-bottom: 10px"
    />
    <el-table v-loading="loading" :data="historyList" size="mini" style="width: 100%">
      <el-table-column label="时间" prop="time" width="180">
        <template slot-scope="scope">
          {{ parseTime(scope.row.time, '{y}-{m}-{d} {h}:{i}:{s}') }}
        </template>
      </el-table-column>
      <el-table-column label="数值" prop="value">
        <template slot-scope="scope">
          <span>{{ scope.row.value }}</span>
          <span v-if="currentUnit" style="color: #909399; margin-left: 4px">{{ currentUnit }}</span>
        </template>
      </el-table-column>
    </el-table>
    <div style="height: 40px">
      <pagination
        v-show="total > 0"
        :total="total"
        :page.sync="queryParams.pageNum"
        :limit.sync="queryParams.pageSize"
        @pagination="getList"
      />
    </div>
  </div>
</template>

<script>
import { listDeviceHistory } from '@/api/iot/deviceHistory'

export default {
  name: 'DeviceHistory',
  props: {
    device: {
      type: Object,
      default: null,
    },
  },
  data() {
    return {
      loading: false,
      deviceInfo: {},
      historyOptions: [],
      historyMeta: {},
      currentUnit: '',
      dateRange: [],
      historyList: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 20,
        serialNumber: null,
        identity: null,
      },
    }
  },
  watch: {
    device: {
      handler(newVal) {
        this.deviceInfo = newVal || {}
        if (this.deviceInfo && this.deviceInfo.deviceId) {
          this.queryParams.serialNumber = this.deviceInfo.serialNumber
          this.resetDateRange()
          this.buildHistoryOptions()
          this.queryParams.pageNum = 1
          this.getList()
        } else {
          this.queryParams.serialNumber = null
          this.historyOptions = []
          this.historyMeta = {}
          this.currentUnit = ''
          this.historyList = []
          this.total = 0
        }
      },
      deep: true,
      immediate: true,
    },
  },
  methods: {
    buildHistoryOptions() {
      this.historyOptions = []
      this.historyMeta = {}
      const cache = this.deviceInfo && this.deviceInfo.cacheThingsModel ? this.deviceInfo.cacheThingsModel : {}
      this.collectHistoryItems(cache.properties || [], '')
      if (!this.historyOptions.length) {
        this.queryParams.identity = null
        this.currentUnit = ''
        return
      }
      if (!this.historyOptions.find((item) => item.value === this.queryParams.identity)) {
        this.queryParams.identity = this.historyOptions[0].value
      }
      this.currentUnit = this.historyMeta[this.queryParams.identity]
        ? this.historyMeta[this.queryParams.identity].unit
        : ''
    },
    collectHistoryItems(items, prefix) {
      if (!Array.isArray(items)) {
        return
      }
      items.forEach((item, index) => {
        const identity = item.id || item.identifier
        const unit = item.datatype && item.datatype.unit ? item.datatype.unit : ''
        const label = prefix ? `${prefix} / ${item.name}` : item.name
        if (item.isHistory === 1 && identity) {
          if (!this.historyMeta[identity]) {
            this.historyOptions.push({ value: identity, label, unit })
          }
          this.historyMeta[identity] = { label, unit }
        }
        if (item.datatype) {
          if (item.datatype.type === 'object' && Array.isArray(item.datatype.params)) {
            this.collectHistoryItems(item.datatype.params, label)
          } else if (item.datatype.type === 'array') {
            if (Array.isArray(item.datatype.arrayParams)) {
              item.datatype.arrayParams.forEach((group, idx) => {
                const groupLabel = `${item.name}[${idx + 1}]`
                const nestedPrefix = prefix ? `${prefix} / ${groupLabel}` : groupLabel
                this.collectHistoryItems(group, nestedPrefix)
              })
            } else if (Array.isArray(item.datatype.arrayModel)) {
              item.datatype.arrayModel.forEach((model, idx) => {
                const modelIdentity = model.id || model.identifier
                const shouldInclude = model.isHistory === 1 || item.isHistory === 1
                if (shouldInclude && modelIdentity) {
                  const modelLabel = prefix
                    ? `${prefix} / ${item.name}[${idx + 1}]`
                    : `${item.name}[${idx + 1}]`
                  if (!this.historyMeta[modelIdentity]) {
                    this.historyOptions.push({ value: modelIdentity, label: modelLabel, unit })
                  }
                  this.historyMeta[modelIdentity] = { label: modelLabel, unit }
                }
              })
            }
          }
        }
      })
    },
    resetDateRange() {
      const end = new Date()
      const start = new Date(end.getTime() - 24 * 60 * 60 * 1000)
      this.dateRange = [this.formatDate(start), this.formatDate(end)]
    },
    formatDate(date) {
      return this.parseTime(date, '{y}-{m}-{d} {h}:{i}:{s}')
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    handleReset() {
      this.resetDateRange()
      this.buildHistoryOptions()
      this.queryParams.pageNum = 1
      this.getList()
    },
    onIdentityChange(val) {
      this.currentUnit = this.historyMeta[val] ? this.historyMeta[val].unit : ''
      this.handleQuery()
    },
    getList() {
      if (!this.queryParams.serialNumber || !this.queryParams.identity) {
        this.historyList = []
        this.total = 0
        return
      }
      const params = {
        ...this.queryParams,
        beginTime: this.dateRange && this.dateRange.length ? this.dateRange[0] : null,
        endTime: this.dateRange && this.dateRange.length ? this.dateRange[1] : null,
      }
      this.loading = true
      listDeviceHistory(params)
        .then((response) => {
          this.historyList = response.rows || []
          this.total = response.total || 0
          this.loading = false
        })
        .catch(() => {
          this.loading = false
        })
    },
  },
}
</script>
