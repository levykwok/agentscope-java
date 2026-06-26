<script setup lang="ts">
import type { ActivityItem } from '../types'

defineProps<{
  items: ActivityItem[]
}>()

function statusLabel(status: ActivityItem['status']) {
  return ({pending: '等待', running: '运行中', success: '完成', skipped: '跳过', warning: '警告', error: '错误'} as const)[status]
}

function duration(item: ActivityItem) {
  if (!item.duration_ms) return ''
  return `${item.duration_ms}ms`
}

function refText(item: ActivityItem) {
  if (!item.refs || Object.keys(item.refs).length === 0) return ''
  return JSON.stringify(item.refs)
}

function detailText(item: ActivityItem) {
  if (!item.detail || Object.keys(item.detail).length === 0) return ''
  return JSON.stringify(item.detail, null, 2)
}

function trackBy(item: ActivityItem) {
  return item.id || `${item.type}:${item.title}`
}

function cssClass(status: ActivityItem['status']) {
  return `status-${status}`
}

function icon(status: ActivityItem['status']) {
  if (status === 'running') return '↻'
  if (status === 'success') return '✓'
  if (status === 'warning') return '!'
  if (status === 'error') return '×'
  if (status === 'skipped') return '·'
  return '…'
}

function isExpanded(item: ActivityItem) {
  return Boolean(refText(item) || detailText(item))
}

function title(item: ActivityItem) {
  return item.title || item.type
}
</script>

<template>
  <section class="panel-section">
    <div class="section-title">
      <span>执行过程</span>
      <small>{{ items.length }} 步</small>
    </div>
    <div class="timeline" v-if="items.length">
      <article v-for="item in items" :key="trackBy(item)" class="timeline-item" :class="cssClass(item.status)">
        <div class="rail"><span>{{ icon(item.status) }}</span></div>
        <div class="timeline-card">
          <div class="timeline-head">
            <strong>{{ title(item) }}</strong>
            <em>{{ statusLabel(item.status) }}</em>
          </div>
          <p v-if="item.summary">{{ item.summary }}</p>
          <div class="timeline-meta">
            <span>{{ item.type }}</span>
            <span v-if="duration(item)">{{ duration(item) }}</span>
          </div>
          <details v-if="isExpanded(item)">
            <summary>查看详情</summary>
            <pre v-if="refText(item)">{{ refText(item) }}</pre>
            <pre v-if="detailText(item)">{{ detailText(item) }}</pre>
          </details>
        </div>
      </article>
    </div>
    <div v-else class="empty-state">开始一轮对话后，这里会显示执行过程。</div>
  </section>
</template>
