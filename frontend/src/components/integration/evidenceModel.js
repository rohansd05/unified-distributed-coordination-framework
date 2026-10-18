import { isLeader, isNodeCrashed } from '@/lib/clusterStatus'
import { findLink } from './integrationLinks'

/**
 * Pure view model for the integration map: what each link's live evidence is right now, read
 * only from existing endpoints (GET /api/cluster, the module overviews, /topic/events). Nothing
 * here starts anything; a read never elects (RoleProvider rule). Where no live value exists the
 * evidence falls back to the backend test that proves the link.
 *
 * `overviews` maps a source id to { status: 'loading' | 'ok' | 'error', data }.
 */

/** Source ids read by useIntegrationEvidence, and the module whose events make each one stale. */
export const SOURCES = {
  election: 'election',
  clocksync: 'clocksync',
  replication: 'replication',
  mapreduce: 'mapreduce',
  mapreduceRuns: 'mapreduce',
  faulttolerance: 'faulttolerance',
  mpi: 'mpi',
  matrix: 'matrix',
  loadbalancing: 'loadbalancing',
}

/** "1", "1 and 2", "1, 2 and 3". */
export function joinIds(ids) {
  if (ids.length <= 1) return ids.join('')
  return `${ids.slice(0, -1).join(', ')} and ${ids[ids.length - 1]}`
}

function ok(overviews, id) {
  const source = overviews?.[id]
  return source?.status === 'ok' ? source.data ?? null : undefined
}

function sourceState(overviews, id) {
  return overviews?.[id]?.status ?? 'loading'
}

function isId(value) {
  return Number.isInteger(value)
}

/** The node holding LEADER, or null (never a crashed node: the backend clears roles on a crash). */
export function leaderIdOf(cluster) {
  const leader = (cluster?.nodes || []).find((node) => isLeader(node) && !isNodeCrashed(node))
  return leader ? leader.id : null
}

function nodeWithRole(cluster, role) {
  const node = (cluster?.nodes || []).find((n) =>
    Array.isArray(n.roles) && n.roles.some((r) => String(r).toUpperCase() === role))
  return node ? node.id : null
}

function crashed(cluster, id) {
  return isNodeCrashed((cluster?.nodes || []).find((n) => n.id === id))
}

function nodeName(cluster, id) {
  return crashed(cluster, id) ? `node ${id} (crashed)` : `node ${id}`
}

/**
 * The six roles that follow the leader (link L1). Each spec reads the holder from the module's
 * own overview and says, in the module's terms, why a holder may differ from the leader.
 */
const ROLE_SPECS = [
  {
    lab: 3,
    source: 'clocksync',
    role: 'Berkeley time daemon',
    read: (data) => ({ holder: data.timeDaemonNodeId ?? null, basis: 'daemon for the next round' }),
    extra: (data, holder) => {
      const last = data.latestRound?.daemonNodeId
      return isId(last) && last !== holder ? `The last finished round ran on node ${last}.` : null
    },
    idle: 'No daemon named yet.',
    mismatch: (holder, leader) =>
      `Node ${holder} is named; the next Berkeley round asks for the leader, node ${leader}.`,
  },
  {
    lab: 5,
    source: 'replication',
    role: 'Replication primary',
    read: (data, cluster) => {
      const shared = nodeWithRole(cluster, 'PRIMARY')
      if (isId(shared)) return { holder: shared, basis: 'shared PRIMARY role' }
      return { holder: data.currentPrimaryNodeId ?? null, basis: 'the node Experiment 5 last made primary' }
    },
    extra: () => null,
    idle: 'No write since the reset: the next accepted write makes the leader primary.',
    mismatch: (holder, leader, cluster) =>
      `${capitalise(nodeName(cluster, holder))} was made primary before the leader changed; the primary ` +
      `moves to node ${leader} at the next accepted write.`,
  },
  {
    lab: 7,
    source: 'mapreduce',
    role: 'MapReduce coordinator',
    read: (data) => ({ holder: data.coordinatorId ?? null, basis: 'coordinator of the next job' }),
    extra: (data, holder) => {
      const run = data.latestRun
      if (!run || !isId(run.coordinatorId) || run.coordinatorId === holder) return null
      return run.state === 'RUNNING'
        ? `The running job keeps node ${run.coordinatorId} as its coordinator.`
        : `The last job ran on node ${run.coordinatorId}.`
    },
    idle: 'No coordinator named yet.',
    mismatch: (holder, leader) =>
      `Node ${holder} is named; the next job asks for the leader, node ${leader}.`,
  },
  {
    lab: 8,
    source: 'faulttolerance',
    role: 'Experiment 8 primary',
    read: (data) => ({
      holder: data.current?.started ? data.current.currentPrimaryId ?? null : null,
      basis: 'acting primary',
      notStarted: !data.current?.started,
    }),
    extra: () => null,
    idle: 'Not started: Start on the Experiment 8 page makes the leader its primary.',
    idleStarted: 'No primary right now: Experiment 8 promotes the leader once the failover completes.',
    mismatch: (holder, leader, cluster, data) => {
      const worst = data?.settings?.worstCaseFailoverMillis
      const bound = Number.isFinite(worst) ? ` (at most about ${(worst / 1000).toFixed(1)} s)` : ''
      return `Failover under way: ${nodeName(cluster, holder)} is primary until Experiment 8 promotes ` +
        `node ${leader}${bound}.`
    },
  },
  {
    lab: 9,
    source: 'mpi',
    role: 'MPI root',
    read: (data) => ({ holder: data.defaultRootNodeId ?? null, basis: rootBasis(data.defaultRootSource) }),
    extra: (data, holder) => {
      const latest = data.latest
      return latest?.rootSource === 'REQUESTED' && isId(latest.rootNodeId) && latest.rootNodeId !== holder
        ? `The last collective used node ${latest.rootNodeId}, requested on the page.`
        : null
    },
    idle: 'No default root yet.',
    mismatch: (holder, leader) =>
      `Node ${holder} is the default root; the next collective asks for the leader, node ${leader}.`,
  },
  {
    lab: 10,
    source: 'matrix',
    role: 'Matrix multiply root',
    read: (data) => ({ holder: data.defaultRootNodeId ?? null, basis: rootBasis(data.defaultRootSource) }),
    extra: (data, holder) => {
      const latest = data.latest
      return latest?.rootSource === 'REQUESTED' && isId(latest.rootNodeId) && latest.rootNodeId !== holder
        ? `The last run used node ${latest.rootNodeId}, requested on the page.`
        : null
    },
    idle: 'No default root yet.',
    mismatch: (holder, leader) =>
      `Node ${holder} is the default root; the next run asks for the leader, node ${leader}.`,
  },
]

function rootBasis(source) {
  if (source === 'LEADER' || source == null) return 'default root'
  return `default root, source ${source}`
}

function capitalise(text) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/**
 * One row per role: { lab, role, holder, basis, state, note, extra }. state is 'match',
 * 'mismatch', 'idle', 'no-leader', 'unavailable' or 'loading'. A mismatch is reported as it is,
 * never hidden.
 */
export function roleRows(cluster, overviews) {
  const leader = leaderIdOf(cluster)
  return ROLE_SPECS.map((spec) => {
    const base = { lab: spec.lab, role: spec.role, holder: null, basis: null, extra: null }
    const status = sourceState(overviews, spec.source)
    if (status === 'error') {
      return { ...base, state: 'unavailable', note: `Couldn’t read Experiment ${spec.lab}’s overview.` }
    }
    const data = ok(overviews, spec.source)
    if (data === undefined || data === null) {
      return { ...base, state: 'loading', note: 'Reading…' }
    }
    const { holder, basis, notStarted } = spec.read(data, cluster)
    const extra = spec.extra(data, holder)
    const row = { ...base, holder, basis, extra }
    if (!isId(holder)) {
      if (!isId(leader)) {
        return { ...row, state: 'no-leader', note: 'No leader yet: the next action asks Experiment 4 to elect one.' }
      }
      const idle = notStarted === false && spec.idleStarted ? spec.idleStarted : spec.idle
      return { ...row, state: 'idle', note: idle }
    }
    if (!isId(leader)) {
      return {
        ...row,
        state: 'mismatch',
        note: `Experiment 4 has no leader right now (a re-election takes a few seconds); ${nodeName(cluster, holder)} still holds this role.`,
      }
    }
    if (holder === leader) {
      return { ...row, state: 'match', note: `Matches the leader, node ${leader}.` }
    }
    return { ...row, state: 'mismatch', note: spec.mismatch(holder, leader, cluster, data) }
  })
}

/** One-line L1 summary: the leader and how many active roles sit on it. */
export function roleSummary(cluster, rows) {
  const leader = leaderIdOf(cluster)
  const active = rows.filter((r) => r.state === 'match' || r.state === 'mismatch')
  const matched = active.filter((r) => r.state === 'match').length
  const waiting = rows.filter((r) => r.state === 'idle').length
  if (!isId(leader)) {
    return active.length
      ? `No leader right now; ${active.length} ${active.length === 1 ? 'role is' : 'roles are'} still held from before.`
      : 'No leader yet: the first action that needs one asks Experiment 4 to elect it.'
  }
  const parts = [`Leader: node ${leader}.`]
  if (active.length) parts.push(`${matched} of ${active.length} active roles match it.`)
  if (waiting) parts.push(`${waiting} not taken yet.`)
  return parts.join(' ')
}

/** "ROUND_ROBIN" -> "round robin". */
function words(value) {
  return String(value || '').toLowerCase().replaceAll('_', ' ')
}

function newer(a, b) {
  if (!a) return b
  if (!b) return a
  return String(a.startedAt || '') >= String(b.startedAt || '') ? a : b
}

function latestBalancerReport(data) {
  const run = data?.latestRun?.report ? { ...data.latestRun.report, startedAt: data.latestRun.startedAt } : null
  const phases = data?.latestComparison?.phases
  const phase = Array.isArray(phases) && phases.length
    ? { ...phases[phases.length - 1], startedAt: data.latestComparison.startedAt, fromComparison: true }
    : null
  return newer(run, phase)
}

function l2(overviews) {
  const lines = []
  const election = ok(overviews, 'election')
  if (election?.servicesStarted) {
    const byPeer = new Map()
    for (const node of election.nodes || []) {
      if (node.status !== 'UP') continue
      for (const peer of node.suspectedPeers || []) {
        byPeer.set(peer, [...(byPeer.get(peer) || []), node.nodeId])
      }
    }
    if (byPeer.size === 0) {
      lines.push('The failure detector runs on every live node; no node suspects a peer right now.')
    } else {
      for (const [peer, observers] of [...byPeer.entries()].sort((a, b) => a[0] - b[0])) {
        const subject = observers.length === 1 ? `Node ${observers[0]} suspects` : `Nodes ${joinIds(observers)} suspect`
        lines.push(`${subject} node ${peer}.`)
      }
    }
    const s = election.settings
    if (Number.isFinite(s?.heartbeatIntervalMillis) && Number.isFinite(s?.heartbeatTimeoutMillis)) {
      lines.push(`Heartbeats every ${s.heartbeatIntervalMillis} ms; a peer silent for ${s.heartbeatTimeoutMillis} ms is suspected.`)
    }
  }
  const run = ok(overviews, 'faulttolerance')?.latestRun
  const milestones = Array.isArray(run?.milestones) ? run.milestones : []
  const at = (kind) => milestones.find((m) => m.kind === kind)
  const detected = at('DETECTED')
  if (run && detected) {
    const elected = at('ELECTED')
    const promoted = at('PROMOTED')
    let text = `Last Experiment 8 failover (run ${run.runId}): node ${detected.nodeId}’s detector suspected ` +
      `primary node ${run.oldPrimaryId} after ${Math.round(detected.offsetMillis)} ms`
    if (elected) text += `; Experiment 4 elected node ${elected.nodeId} at ${Math.round(elected.offsetMillis)} ms`
    if (promoted) text += `; Experiment 8 promoted node ${promoted.nodeId} at ${Math.round(promoted.offsetMillis)} ms`
    lines.push(`${text}.`)
  }
  return lines
}

function l3(overviews) {
  const report = latestBalancerReport(ok(overviews, 'loadbalancing'))
  if (!report || !Array.isArray(report.nodes)) return []
  const perNode = report.nodes
    .filter((n) => Number.isFinite(n.requests))
    .map((n) => `node ${n.nodeId} ${n.requests}`)
    .join(', ')
  const source = report.fromComparison ? 'Last comparison phase' : 'Last run'
  return [
    `${source} (${words(report.strategy)}): ${report.served} of ${report.requestCount} requests ran on ` +
      `Experiment 2 executors.`,
    `Requests per node: ${perNode}.`,
  ]
}

function remoteDelivered(transfers) {
  return (Array.isArray(transfers) ? transfers : [])
    .filter((t) => t.fromNodeId !== t.toNodeId && t.status === 'DELIVERED')
}

function l4(overviews, events) {
  const lines = []
  const list = Array.isArray(events) ? events : []
  const latest = list.reduce((best, e) => (!best || e.sequence > best.sequence ? e : best), null)
  if (latest && Number.isFinite(latest.lamportTime)) {
    const where = latest.nodeId === 0 ? 'the cluster clock' : `node ${latest.nodeId}`
    lines.push(`Latest event: Lamport ${latest.lamportTime} on ${where} (${words(latest.type)}, ${latest.module}).`)
  }
  const stamped = [ok(overviews, 'mpi')?.latest?.transfers, ok(overviews, 'matrix')?.latest?.scatter?.transfers]
    .map((transfers) => remoteDelivered(transfers).find((t) => Number.isFinite(t.senderLamportAtSend)))
    .find(Boolean)
  if (stamped) {
    lines.push(
      `An RMI transfer from node ${stamped.fromNodeId} to node ${stamped.toNodeId}: sent at Lamport ` +
      `${stamped.senderLamportAtSend}, reply stamped ${stamped.replyLamport}, sender after the reply ` +
      `${stamped.lamportAfterReply}.`,
    )
  }
  return lines
}

function l5(overviews) {
  const runs = ok(overviews, 'mapreduceRuns')
  const run = (Array.isArray(runs) ? runs : []).find((r) => r.inputType === 'EVENT_LOG')
  if (!run) return []
  const overview = ok(overviews, 'mapreduce')
  const job = (overview?.jobs || []).find((j) => j.id === run.jobId)
  const lines = [`Run ${run.runId}: ${job?.title || run.jobId} over the live cluster event log, ${words(run.state)}.`]
  const latest = overview?.latestRun
  if (latest?.runId === run.runId && Number.isFinite(latest.report?.inputLines)) {
    lines.push(`${latest.report.inputLines} events were read from the export.`)
  }
  return lines
}

function l6(overviews) {
  const latest = ok(overviews, 'matrix')?.latest
  if (!latest) return []
  const scatter = remoteDelivered(latest.scatter?.transfers).length
  const broadcast = remoteDelivered(latest.broadcast?.transfers).length
  const gathered = remoteDelivered(latest.gather).length
  const lines = [
    `Run ${latest.runId}: size ${latest.size} from root node ${latest.rootNodeId}, ${words(latest.outcome)}.`,
    `Experiment 9 scatter ${words(latest.scatter?.outcome || 'not run')} (${scatter} remote transfers) and ` +
      `broadcast ${words(latest.broadcast?.outcome || 'not run')} (${broadcast}), over Experiment 1 RMI; ` +
      `${gathered} blocks of C fetched by RMI.`,
  ]
  if (latest.verification) {
    lines.push(latest.verification.passed ? 'The result matches the sequential product.' : 'The result did not verify.')
  }
  return lines
}

const NO_LIVE = {
  L2: 'The failure detector starts with the first election.',
  L3: 'No balancer run since the reset.',
  L4: 'No event received yet.',
  L5: 'No MapReduce run has read the live event log since the reset; choose “Live cluster event log” on the Experiment 7 page.',
  L6: 'No matrix run since the reset.',
}

/**
 * Evidence for one link: { kind: 'live' | 'test', lines, test }. 'test' means no live value
 * exists right now, so the map says "wired, verified by <test>" instead.
 */
export function linkEvidence(linkId, { cluster, overviews, events } = {}) {
  const link = findLink(linkId)
  if (!link) return { kind: 'test', lines: [], test: null }
  const test = link.tests[0]
  if (linkId === 'L1') {
    const rows = roleRows(cluster, overviews)
    const live = rows.some((r) => r.state === 'match' || r.state === 'mismatch') || leaderIdOf(cluster) !== null
    return { kind: live ? 'live' : 'test', lines: [roleSummary(cluster, rows)], rows, test }
  }
  const lines = { L2: l2, L3: l3, L4: (o) => l4(o, events), L5: l5, L6: l6 }[linkId](overviews)
  if (lines.length) return { kind: 'live', lines, test }
  return { kind: 'test', lines: [NO_LIVE[linkId]], test }
}

/** A short status line for the link list: live headline, or the test that proves it. */
export function evidenceHeadline(evidence) {
  if (evidence.kind === 'live') return evidence.lines[0]
  return `Wired, verified by ${evidence.test}`
}
