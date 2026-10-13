import { failureKindLabel, formatNanos, operationLabel, stageLabel } from './labels'

/**
 * Pure view models for the Experiment 9 page: no React, no network. Every figure comes from the
 * backend as it was sent; nothing here measures, clamps or invents a number. No vector values
 * exist on the wire (only checksums), so none are shown.
 */

/**
 * True when some node is up but does not export its CollectiveEndpoint (a fresh backend, a failed
 * start, or after a cluster reset). A crashed node does not count: it cannot be started.
 */
export function needsStart(overview) {
  return Array.isArray(overview?.participants)
    && overview.participants.some((node) => node.nodeStatus === 'UP' && node.collectiveEndpointExported === false)
}

/** The overview's entry for an operation (description, minimumCount), or null. */
export function operationEntry(overview, operation) {
  return (Array.isArray(overview?.operations) ? overview.operations : []).find((entry) => entry.operation === operation) ?? null
}

const isEmpty = (value) => value === undefined || value === null || String(value).trim() === ''

/** The body of POST /collectives: seed and rootNode only when filled in (empty means the backend's default). */
export function collectiveBody({ operation, count, seed, rootNode }) {
  const body = { operation, count: Number(count) }
  if (!isEmpty(seed)) body.seed = Number(seed)
  if (!isEmpty(rootNode)) body.rootNode = Number(rootNode)
  return body
}

/**
 * The checks the page can make before sending, in the backend's own words, with every limit from
 * the overview. A count below an operation's minimumCount is NOT refused here: the backend answers
 * it with a recorded REFUSED collective, which is part of the demonstration. A crashed or unknown
 * root is left to the backend too (409 or 404). The backend checks everything again.
 *
 * @returns {Record<string, string>} field name to message; empty when the form can be sent
 */
export function validateCollective(form, overview) {
  const errors = {}
  if (isEmpty(form.operation)) errors.operation = 'operation is required'
  const maxCount = overview?.limits?.maxPayloadElements
  if (isEmpty(form.count)) {
    errors.count = 'count is required'
  } else {
    const count = Number(form.count)
    if (!Number.isSafeInteger(count) || count < 1 || (typeof maxCount === 'number' && count > maxCount)) {
      errors.count = `count must be 1-${maxCount}, was ${String(form.count).trim()}`
    }
  }
  if (!isEmpty(form.seed)) {
    const maxSeed = overview?.limits?.maxSeed
    const seed = Number(form.seed)
    if (!Number.isSafeInteger(seed) || seed < 0 || (typeof maxSeed === 'number' && seed > maxSeed)) {
      errors.seed = `seed must be 0-${maxSeed}, was ${String(form.seed).trim()}`
    }
  }
  return errors
}

/** The remote transfers of a collective (the root's LOCAL part never crosses the network). */
const remoteTransfers = (collective) =>
  (Array.isArray(collective?.transfers) ? collective.transfers : []).filter((transfer) => transfer.status !== 'LOCAL')

/** The node a transfer talks to, seen from the root. */
export const peerOf = (transfer) => (transfer.direction === 'FROM_RANK' ? transfer.fromNodeId : transfer.toNodeId)

/** How the remote transfers ended: delivered, failed, not attempted, and how many there were. */
export function transferCounts(collective) {
  const remote = remoteTransfers(collective)
  const count = (status) => remote.filter((transfer) => transfer.status === status).length
  return { remote: remote.length, delivered: count('DELIVERED'), failed: count('FAILED'), notAttempted: count('NOT_ATTEMPTED') }
}

/**
 * The ranks in rank order (rank = position in rankNodeIds), each as root or participant, then the
 * skipped nodes (crashed at the start, so not ranks).
 */
export function rankRows(collective) {
  const ranks = Array.isArray(collective?.rankNodeIds) ? collective.rankNodeIds : []
  const skipped = Array.isArray(collective?.skippedNodeIds) ? collective.skippedNodeIds : []
  return [
    ...ranks.map((nodeId, rank) => ({ key: `rank-${rank}`, rank, nodeId, role: nodeId === collective.rootNodeId ? 'root' : 'participant' })),
    ...skipped.map((nodeId) => ({ key: `skipped-${nodeId}`, rank: null, nodeId, role: 'skipped' })),
  ]
}

export const MAP_SIZE = { width: 360, height: 280 }
const MAP_RADIUS = 105

/** A node's state on the map: down (crashed), not-started (endpoint not exported) or up. */
function nodeState(node) {
  if (node.nodeStatus === 'CRASHED') return 'down'
  if (!node.collectiveEndpointExported) return 'not-started'
  return 'up'
}

/**
 * The fan diagram: the root in the centre, every other node on a circle around it (count from the
 * overview), and one arrow per remote transfer of the collective, in rank order: root to rank for
 * broadcast and scatter, rank to root for gather. Skipped nodes get no arrow. With no collective,
 * the default root is in the centre and there are no arrows.
 */
export function fanModel(overview, collective) {
  const participants = Array.isArray(overview?.participants) ? overview.participants : []
  const rootId = collective?.rootNodeId ?? overview?.defaultRootNodeId ?? null
  const cx = MAP_SIZE.width / 2
  const cy = MAP_SIZE.height / 2
  const others = participants.filter((node) => node.nodeId !== rootId)
  const skipped = new Set(Array.isArray(collective?.skippedNodeIds) ? collective.skippedNodeIds : [])
  const nodes = participants.map((node) => {
    const index = others.indexOf(node)
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / Math.max(1, others.length)
    const isRoot = node.nodeId === rootId
    return {
      id: node.nodeId,
      x: isRoot ? cx : Math.round(cx + MAP_RADIUS * Math.cos(angle)),
      y: isRoot ? cy : Math.round(cy + MAP_RADIUS * Math.sin(angle)),
      root: isRoot,
      state: nodeState(node),
      skipped: skipped.has(node.nodeId),
      leader: node.leader === true,
    }
  })
  const ids = new Set(nodes.map((node) => node.id))
  const arrows = remoteTransfers(collective)
    .filter((transfer) => ids.has(peerOf(transfer)) && ids.has(rootId))
    .map((transfer, order) => ({
      key: `${transfer.rank}`,
      rank: transfer.rank,
      peer: peerOf(transfer),
      inward: transfer.direction === 'FROM_RANK',
      status: transfer.status,
      failureKind: transfer.failureKind ?? null,
      order,
    }))
  return { nodes, arrows, rootId, hasCollective: Boolean(collective) }
}

/**
 * One slot per rank for the latency chart, in rank order. Only a DELIVERED transfer has a latency
 * (a bar); a FAILED one has only its time to failure (a cross, never a bar), a NOT_ATTEMPTED one
 * nothing (an empty outline), and the root's LOCAL part nothing (a dot).
 */
export function chartModel(collective) {
  const transfers = Array.isArray(collective?.transfers) ? collective.transfers : []
  const slots = transfers.map((transfer) => ({
    key: `${transfer.rank}`,
    rank: transfer.rank,
    nodeId: peerOf(transfer),
    status: transfer.status,
    nanos: transfer.status === 'DELIVERED' ? transfer.latencyNanos : null,
    timeToFailureNanos: transfer.status === 'FAILED' ? transfer.timeToFailureNanos : null,
    failureKind: transfer.failureKind ?? null,
  }))
  const measured = slots.filter((slot) => typeof slot.nanos === 'number').map((slot) => slot.nanos)
  return { slots, max: measured.length > 0 ? Math.max(...measured) : null }
}

/**
 * Rows for the history table, newest first as the backend sent them. Ids restart after a cluster
 * reset, so a row is keyed by id and start time, never by id alone. The entry itself is the whole
 * CollectiveDto, shown when the row is selected.
 */
export function historyRows(history) {
  return (Array.isArray(history) ? history : []).map((entry) => ({
    key: `${entry.id}-${entry.startedAt}`,
    id: entry.id,
    startedAt: entry.startedAt ?? null,
    operation: entry.operation,
    count: entry.count,
    rootNodeId: entry.rootNodeId,
    outcome: entry.outcome,
    totalNanos: entry.totalNanos ?? null,
    counts: transferCounts(entry),
    entry,
  }))
}

const LINEAR_TEXT = 'The root makes every transfer itself, one rank after another, so the total time grows with the number of '
  + 'nodes. Real MPI libraries use trees so the root does not do all the work.'
const CRASH_TEXT = 'A node crashed before a collective is skipped. A node that cannot be reached during one fails with a real '
  + 'RemoteException, reported with its stage and kind, while the other ranks still get theirs: nothing hangs.'
const REFUSED_TEXT = 'Scatter and gather need at least one element per live node. With fewer, the backend refuses the collective '
  + 'before sending anything, and keeps the refusal in the history.'

/**
 * The three "What to notice" callouts. Each is a general sentence; a measured figure is added only
 * when the history (newest first) really holds it.
 */
export function noticeModel(history) {
  const list = Array.isArray(history) ? history : []
  const callouts = []

  const timed = list.find((entry) => typeof entry.totalNanos === 'number' && transferCounts(entry).remote > 0)
  if (timed) {
    const counts = transferCounts(timed)
    callouts.push(`${LINEAR_TEXT} Measured here: ${operationLabel(timed.operation).toLowerCase()} ${timed.id} took `
      + `${formatNanos(timed.totalNanos)} in total for ${counts.remote} remote ${counts.remote === 1 ? 'transfer' : 'transfers'}.`)
  } else {
    callouts.push(LINEAR_TEXT)
  }

  const withFailure = list.find((entry) => remoteTransfers(entry).some((transfer) => transfer.status === 'FAILED'))
  const withSkip = list.find((entry) => Array.isArray(entry.skippedNodeIds) && entry.skippedNodeIds.length > 0 && entry.outcome !== 'REFUSED')
  if (withFailure) {
    const failed = remoteTransfers(withFailure).find((transfer) => transfer.status === 'FAILED')
    const time = formatNanos(failed.timeToFailureNanos)
    callouts.push(`${CRASH_TEXT} Measured here: in collective ${withFailure.id} the transfer with node ${peerOf(failed)} failed at `
      + `the ${stageLabel(failed.failureStage)} with ${failureKindLabel(failed.failureKind).toLowerCase()}`
      + `${time ? ` after ${time}` : ''}.`)
  } else if (withSkip) {
    callouts.push(`${CRASH_TEXT} Measured here: collective ${withSkip.id} skipped node${withSkip.skippedNodeIds.length === 1 ? '' : 's'} `
      + `${withSkip.skippedNodeIds.join(', ')}.`)
  } else {
    callouts.push(CRASH_TEXT)
  }

  const refused = list.find((entry) => entry.outcome === 'REFUSED' && entry.refusal?.message)
  callouts.push(refused ? `${REFUSED_TEXT} Latest refusal: ${refused.refusal.message}` : REFUSED_TEXT)
  return callouts
}
