import { isNodeCrashed } from '@/lib/clusterStatus'
import { cellStateLabel, cellStateMeaning, valueText } from './labels'

/*
 * Pure view models for the Experiment 5 page: they turn whatever the backend sent, including
 * null, empty and partly filled data, into exactly what is drawn, so every edge case is tested
 * without rendering. Field names and enums come from the E5c fixtures
 * (frontend/src/test/fixtures/replication), never from guesses.
 */

const asArray = (value) => (Array.isArray(value) ? value : [])

/** "Node 3, stale: holds 1000 (Lamport 4, written by node 1)". */
export function cellDescription(nodeId, isReference, state, item) {
  const who = `Node ${nodeId}${isReference ? ', the primary' : ''}`
  if (state === 'UNREACHABLE') {
    return `${who}, unreachable: ${cellStateMeaning(state)}`
  }
  const holds = item
    ? `holds ${valueText(item.value)} (Lamport ${item.lamportTime}, written by node ${item.originNode})`
    : 'holds nothing'
  return `${who}, ${cellStateLabel(state).toLowerCase()}: ${holds}`
}

/**
 * The replicas side by side (GET /replicas), with the reference replica (the node last made
 * primary) first and every other node in node order.
 *
 * @param {object|null} replicas a ReplicasDto
 * @returns {{ loaded: boolean, referenceNodeId: number|null, consistent: boolean|null,
 *   divergences: number|null, columns: object[], rows: object[], unreachable: number[] }}
 */
export function gridModel(replicas) {
  if (!replicas) {
    return { loaded: false, referenceNodeId: null, consistent: null, divergences: null, columns: [], rows: [], unreachable: [] }
  }
  const referenceNodeId = replicas.referenceNodeId ?? null
  const order = (list, idOf) => [...list].sort((a, b) => {
    const aRef = idOf(a) === referenceNodeId ? 0 : 1
    const bRef = idOf(b) === referenceNodeId ? 0 : 1
    return aRef - bRef || idOf(a) - idOf(b)
  })
  const columns = order(asArray(replicas.replicas), (c) => c.nodeId).map((column) => ({
    nodeId: column.nodeId,
    nodeStatus: column.nodeStatus,
    isReference: column.nodeId === referenceNodeId,
    reachable: Boolean(column.reachable),
    epoch: column.epoch ?? null,
    itemCount: column.itemCount ?? null,
    error: column.error ?? null,
  }))
  const rows = asArray(replicas.rows).map((row) => ({
    key: row.key,
    cells: order(asArray(row.cells), (c) => c.nodeId).map((cell) => {
      const isReference = cell.nodeId === referenceNodeId
      const state = cell.state ?? null
      return {
        nodeId: cell.nodeId,
        isReference,
        state,
        label: cellStateLabel(state),
        item: cell.item ?? null,
        description: cellDescription(cell.nodeId, isReference, state, cell.item ?? null),
      }
    }),
  }))
  return {
    loaded: true,
    referenceNodeId,
    consistent: replicas.consistent ?? null,
    divergences: replicas.divergences ?? null,
    columns,
    rows,
    unreachable: columns.filter((column) => !column.reachable).map((column) => column.nodeId),
  }
}

/** One sentence for the grid's live region and header. */
export function gridSummary(model) {
  if (!model.loaded) {
    return 'The replicas have not been read yet.'
  }
  const down = model.unreachable.length
  const downText = down === 0 ? '' : ` ${down} ${down === 1 ? 'replica is' : 'replicas are'} unreachable.`
  if (model.consistent === null) {
    return `Not compared: there is no readable primary yet.${downText}`
  }
  if (model.consistent) {
    return `Every reachable replica matches the primary (node ${model.referenceNodeId}).${downText}`
  }
  const n = model.divergences ?? 0
  return `${n} ${n === 1 ? 'difference' : 'differences'} from the primary (node ${model.referenceNodeId}).${downText}`
}

/**
 * The primary's history as a small timeline: each PRIMARY_SELECTED event in causal order (the
 * last six), the per-source catch-up counts for the latest one when the overview's lastTakeover
 * matches it, and a pending marker when the selector already picks another node.
 *
 * @param {object[]} events the module's events (any types; others are ignored)
 * @param {object|null} overview a ReplicationOverviewDto
 */
export function timelineModel(events, overview) {
  const lastTakeover = overview?.lastTakeover ?? null
  let entries = asArray(events)
    .filter((event) => event?.type === 'PRIMARY_SELECTED')
    .map((event) => ({
      id: `event-${event.sequence}`,
      nodeId: event.nodeId,
      previousNodeId: event.data?.previousPrimaryId ?? null,
      lamportTime: event.lamportTime,
      appliedFromCatchUp: event.data?.appliedFromCatchUp ?? null,
      catchUpSources: asArray(event.data?.catchUpSources),
      pushedTo: asArray(event.data?.pushedTo),
      catchUps: [],
    }))
  if (entries.length === 0 && lastTakeover) {
    // The event history was trimmed or cleared, but the overview still knows the latest selection.
    entries = [{
      id: 'last-takeover',
      nodeId: lastTakeover.newPrimaryNodeId,
      previousNodeId: lastTakeover.previousPrimaryNodeId ?? null,
      lamportTime: lastTakeover.lamportTime,
      appliedFromCatchUp: lastTakeover.appliedFromCatchUp,
      catchUpSources: asArray(lastTakeover.catchUps).map((c) => c.sourceNodeId),
      pushedTo: asArray(lastTakeover.pushes).map((p) => p.targetNodeId),
      catchUps: [],
    }]
  }
  entries = entries.slice(-6)
  const latest = entries[entries.length - 1]
  if (latest && lastTakeover && latest.nodeId === lastTakeover.newPrimaryNodeId
      && latest.lamportTime === lastTakeover.lamportTime) {
    latest.catchUps = asArray(lastTakeover.catchUps)
  }
  const pending = overview?.takeoverPending
    ? { nodeId: overview.primaryNodeId ?? null, fromNodeId: overview.currentPrimaryNodeId ?? null }
    : null
  return { entries, pending }
}

/**
 * The asynchronous replication window: open exactly while the latest write's replicationState
 * is PENDING, as the backend reports it. Never opened or closed by a timer (R7).
 */
export function pendingWindow(overview) {
  const write = overview?.latestWrite ?? null
  const open = write?.replicationState === 'PENDING'
  return {
    open,
    write: open ? write : null,
    delayMillis: open ? write.simulatedDelayMillis : null,
    reason: open ? (write.simulatedReason ?? overview?.asyncDelayReason ?? null) : null,
  }
}

/** Nodes that are up and not the selector's primary: the only valid anti-entropy or stale-update targets. */
export function liveBackups(overview) {
  return asArray(overview?.nodes).filter((node) => node.role === 'BACKUP' && !isNodeCrashed({ status: node.nodeStatus }))
}
