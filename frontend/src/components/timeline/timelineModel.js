import { causalCompare } from '@/hooks/useEventStream'
import { formatEventType } from '@/components/events/eventFormatters'
import { EXPERIMENTS } from '@/lib/experiments'

/*
 * The Timeline page's pure logic: merging, ordering, filtering, readable sentences, the leader
 * chain and scenario bands. Nothing here reads a clock or invents a value.
 */

/** Events loaded from GET /api/events when the page opens (and again after a reconnect). */
export const INITIAL_LIMIT = 500
/** Most events the browser keeps; the oldest by bus sequence go first. */
export const TIMELINE_CAP = 2000

export const ORDER_CAUSAL = 'causal'
export const ORDER_ARRIVAL = 'arrival'

/**
 * Merges incoming events into `prev`: deduplicated by sequence, in bus-sequence order, nothing
 * older than the latest CLUSTER_RESET (the backend clears its history there too), at most `cap`.
 */
export function mergeEvents(prev, incoming, cap = TIMELINE_CAP) {
  const bySequence = new Map(prev.map((e) => [e.sequence, e]))
  for (const e of incoming || []) {
    if (e && Number.isFinite(e.sequence)) bySequence.set(e.sequence, e)
  }
  let merged = [...bySequence.values()].sort((a, b) => a.sequence - b.sequence)
  const reset = merged.findLastIndex((e) => e.type === 'CLUSTER_RESET')
  if (reset > 0) merged = merged.slice(reset)
  return merged.length > cap ? merged.slice(-cap) : merged
}

/** (lamportTime, nodeId) with ties broken by bus sequence, or bus sequence alone. */
export function orderEvents(events, order = ORDER_CAUSAL) {
  return [...events].sort(order === ORDER_ARRIVAL ? (a, b) => a.sequence - b.sequence : causalCompare)
}

// ---------- filters in the URL query ----------

/** Query parameter names, documented for deep links from Overview and Scenarios. */
export const PARAM = { modules: 'modules', nodes: 'nodes', q: 'q', order: 'order' }

function list(value) {
  return value ? value.split(',').map((v) => v.trim()).filter(Boolean) : []
}

/** Reads `?modules=a,b&nodes=0,4&q=text&order=arrival`; bad values are ignored. */
export function parseFilters(searchParams) {
  return {
    modules: list(searchParams.get(PARAM.modules)),
    nodes: list(searchParams.get(PARAM.nodes)).map(Number).filter((n) => Number.isInteger(n) && n >= 0),
    q: searchParams.get(PARAM.q) || '',
    order: searchParams.get(PARAM.order) === ORDER_ARRIVAL ? ORDER_ARRIVAL : ORDER_CAUSAL,
  }
}

/** The inverse of parseFilters; defaults are left out so a plain /timeline stays plain. */
export function filtersToParams({ modules = [], nodes = [], q = '', order = ORDER_CAUSAL }) {
  const params = {}
  if (modules.length) params[PARAM.modules] = modules.join(',')
  if (nodes.length) params[PARAM.nodes] = nodes.join(',')
  if (q) params[PARAM.q] = q
  if (order === ORDER_ARRIVAL) params[PARAM.order] = ORDER_ARRIVAL
  return params
}

export function applyFilters(events, { modules = [], nodes = [], q = '' }) {
  const text = q.trim().toLowerCase()
  return events.filter((e) =>
    (!modules.length || modules.includes(e.module))
    && (!nodes.length || nodes.includes(e.nodeId))
    && (!text || `${e.type} ${e.message ?? ''} ${describeEvent(e)}`.toLowerCase().includes(text)))
}

// ---------- labels ----------

const MODULE_LABELS = {
  cluster: 'Cluster',
  scenario: 'Scenarios',
  ...Object.fromEntries(EXPERIMENTS.map((x) => [x.id, `Exp ${x.lab}: ${x.title}`])),
}

/** Module filter options: the catalog in lab order, cluster, scenarios, then anything else seen. */
export function moduleOptions(events) {
  const ids = [...EXPERIMENTS.map((x) => x.id), 'cluster', 'scenario']
  for (const e of events) if (!ids.includes(e.module)) ids.push(e.module)
  return ids.map((id) => ({ id, label: MODULE_LABELS[id] ?? id }))
}

export function nodeLabel(nodeId) {
  return nodeId === 0 ? 'Cluster' : `Node ${nodeId}`
}

// ---------- readable sentences ----------

function was(previous) {
  return previous == null ? '' : ` (was node ${previous})`
}

function pick(data, ...keys) {
  for (const k of keys) if (data[k] != null && data[k] !== '') return data[k]
  return null
}

function roleSentence(role, e) {
  const d = e.data || {}
  const after = d.cause === 'LEADER_CRASHED' && d.previousNodeId != null
    ? `, after node ${d.previousNodeId} crashed` : ''
  return `Node ${e.nodeId} became the ${role}${was(d.previousNodeId)}${after}`
}

function stepName(d) {
  const name = pick(d, 'stepTitle', 'title', 'stepName', 'stepId')
  const index = pick(d, 'stepIndex', 'step')
  return [index != null ? `Step ${index}` : 'Step', name].filter(Boolean).join(': ')
}

const SENTENCES = {
  LEADER_CHANGED: (e, d) => (d.leaderId == null
    ? `No leader${d.previousLeaderId == null ? '' : `: node ${d.previousLeaderId} is no longer leader`}`
    : `Node ${d.leaderId} became leader${was(d.previousLeaderId)}`),
  TIME_DAEMON_CHANGED: (e) => roleSentence('Berkeley time daemon', e),
  COORDINATOR_CHANGED: (e) => roleSentence('MapReduce coordinator', e),
  MPI_ROOT_CHANGED: (e) => roleSentence('MPI root', e),
  BERKELEY_ROUND_ABORTED: (e, d) =>
    `Berkeley round${d.roundId == null ? '' : ` ${d.roundId}`} aborted: the time daemon, node ${e.nodeId}, went down`,
  PRIMARY_PROMOTED: (e, d) => `Node ${e.nodeId} became the fault-tolerance primary`
    + `${d.epoch == null ? '' : ` at epoch ${d.epoch}`}${was(d.previousPrimaryId)}`,
  PRIMARY_SELECTED: (e) => `Node ${e.nodeId} became the replication primary${was(e.peerId)}`,
  REPLICATION_ROLES_CHANGED: (e, d) => (d.primaryId == null
    ? `No replication primary${d.previousPrimaryId == null ? '' : ` (was node ${d.previousPrimaryId})`}`
    : `Node ${d.primaryId} holds the replication primary role${was(d.previousPrimaryId)}`
      + (Array.isArray(d.backupIds) && d.backupIds.length ? `; backups ${d.backupIds.join(', ')}` : '; no backups')),
  HANDOVER_STARTED: (e, d) => `Node ${e.nodeId} stopped serving so node ${d.leaderId ?? e.peerId}, the elected leader, can take over`
    + (d.epoch == null ? '' : ` at epoch ${d.epoch}`),
  NODE_CRASHED: (e) => `Node ${e.nodeId} crashed`,
  NODE_RECOVERED: (e) => `Node ${e.nodeId} recovered`,
  CLUSTER_RESET: () => 'Cluster reset: every node up, clocks and history restarted',
  SCENARIO_STARTED: (e, d) => {
    const name = pick(d, 'scenarioTitle', 'title', 'scenarioName', 'scenarioId')
    return name ? `Scenario "${name}" started` : 'Scenario started'
  },
  STEP_STARTED: (e, d) => `${stepName(d)} started`,
  STEP_PASSED: (e, d) => `${stepName(d)} passed`,
  STEP_FAILED: (e, d) => `${stepName(d)} failed`,
  STEP_SKIPPED: (e, d) => `${stepName(d)} skipped`,
  SCENARIO_FINISHED: (e, d) => {
    const outcome = pick(d, 'outcome', 'result', 'status')
    return outcome ? `Scenario finished: ${String(outcome).toLowerCase()}` : 'Scenario finished'
  },
}

export function isKnownType(type) {
  return Object.hasOwn(SENTENCES, type)
}

/** A readable sentence for a known type; otherwise the event's own message, or its raw type. */
export function describeEvent(e) {
  const sentence = SENTENCES[e.type]
  if (sentence) {
    try {
      return sentence(e, e.data || {})
    } catch {
      // a malformed payload falls through to the message
    }
  }
  return e.message || e.type || 'Event'
}

export function typeLabel(type) {
  return isKnownType(type) ? formatEventType(type) : type
}

// ---------- the leader chain ----------

/** The node that a role-change event hands its role to, or null for any other event. */
export function roleTarget(e) {
  switch (e.type) {
    case 'TIME_DAEMON_CHANGED':
    case 'COORDINATOR_CHANGED':
    case 'MPI_ROOT_CHANGED':
    case 'PRIMARY_PROMOTED':
    case 'PRIMARY_SELECTED':
      return e.nodeId
    case 'REPLICATION_ROLES_CHANGED':
      return e.data?.primaryId ?? null
    case 'HANDOVER_STARTED':
      return e.data?.leaderId ?? e.peerId ?? null
    default:
      return null
  }
}

/** What each role-change type hands over, for the leader-chain summary. */
export const ROLE_NAMES = {
  TIME_DAEMON_CHANGED: 'time daemon',
  COORDINATOR_CHANGED: 'MapReduce coordinator',
  MPI_ROOT_CHANGED: 'MPI root',
  PRIMARY_PROMOTED: 'fault-tolerance primary',
  PRIMARY_SELECTED: 'replication primary',
  REPLICATION_ROLES_CHANGED: 'replication primary role',
  HANDOVER_STARTED: 'fault-tolerance handover',
}

/** Marker shape on the lanes; every shape also has a text legend. */
export function eventKind(e) {
  if (e.type === 'LEADER_CHANGED') return 'leader'
  if (roleTarget(e) != null) return 'role'
  if (e.type === 'NODE_CRASHED') return 'crash'
  if (e.type === 'NODE_RECOVERED' || e.type === 'CLUSTER_RESET') return 'recover'
  if (e.type === 'BERKELEY_ROUND_ABORTED' || e.type === 'STEP_FAILED') return 'warning'
  if (/^(SCENARIO|STEP)_/.test(e.type || '')) return 'scenario'
  return 'other'
}

function leaderOf(e) {
  return e.data?.leaderId ?? null
}

/**
 * A LEADER_CHANGED with a new leader plus every role-change event, later by bus sequence and
 * before the next LEADER_CHANGED, that hands a role to that same node. The anchor is the event
 * with `anchorSequence` if it is such a LEADER_CHANGED, else the latest LEADER_CHANGED (none
 * when the latest one is a vacancy).
 *
 * @returns {{ anchor: object, leaderId: number, members: Array<object>, sequences: Set<number> } | null}
 */
export function leaderChain(events, anchorSequence = null) {
  const bySequence = [...events].sort((a, b) => a.sequence - b.sequence)
  const changes = bySequence.filter((e) => e.type === 'LEADER_CHANGED')
  let anchor = changes.find((e) => e.sequence === anchorSequence && leaderOf(e) != null)
  if (!anchor) anchor = changes.at(-1)
  if (!anchor || leaderOf(anchor) == null) return null
  const next = changes.find((e) => e.sequence > anchor.sequence)
  const leaderId = leaderOf(anchor)
  const members = bySequence.filter((e) => e.sequence > anchor.sequence
    && (!next || e.sequence < next.sequence)
    && roleTarget(e) === leaderId)
  return { anchor, leaderId, members, sequences: new Set([anchor.sequence, ...members.map((e) => e.sequence)]) }
}

// ---------- scenario bands ----------

/**
 * Scenario runs, from SCENARIO_STARTED to SCENARIO_FINISHED by bus sequence, matched on
 * data.runId when both carry one. An unfinished run has `endSequence` null.
 */
export function scenarioBands(events) {
  const bands = []
  const open = []
  for (const e of [...events].sort((a, b) => a.sequence - b.sequence)) {
    const runId = e.data?.runId ?? null
    if (e.type === 'SCENARIO_STARTED') {
      const name = pick(e.data || {}, 'scenarioTitle', 'title', 'scenarioName', 'scenarioId')
      const band = { key: `scenario-${e.sequence}`, runId, title: name ? `Scenario: ${name}` : 'Scenario', startSequence: e.sequence, endSequence: null, outcome: null }
      bands.push(band)
      open.push(band)
    } else if (e.type === 'SCENARIO_FINISHED') {
      const i = runId == null ? open.length - 1 : open.findLastIndex((b) => b.runId === runId)
      if (i >= 0) {
        const [band] = open.splice(i, 1)
        band.endSequence = e.sequence
        band.outcome = e.data?.outcome ?? e.data?.result ?? null
      }
    }
  }
  return bands
}
