import { isLeader } from '@/lib/clusterStatus'
import { algorithmLabel, formatMillis, secondsFrom } from './labels'

/** At most this many message arrows are drawn, the newest ones of the round. */
export const MAX_ARCS = 8

/**
 * The failure detector's state as the backend reports it: 'unknown' before the first load,
 * 'idle' until the first election request starts the election services (E4c lazy start),
 * then 'running'.
 */
export function detectorState(overview) {
  if (!overview) {
    return 'unknown'
  }
  return overview.servicesStarted ? 'running' : 'idle'
}

/** Nodes that are up, from the overview; null before the first load. */
export function liveNodeCount(overview) {
  return Array.isArray(overview?.nodes) ? overview.nodes.filter((node) => node.status === 'UP').length : null
}

/** True when exactly one node is up: a Ring election then cannot elect anyone (E4a ring of one). */
export function isRingOfOne(overview) {
  return liveNodeCount(overview) === 1
}

/** The leader from cluster state (GET /api/cluster roles), never from the page: a node id or null. */
export function leaderIdFrom(cluster) {
  const node = Array.isArray(cluster?.nodes) ? cluster.nodes.find(isLeader) : undefined
  return node ? node.id : null
}

/**
 * Places nodes evenly on a circle, the first at the top, in id order.
 *
 * @returns {Array<object>} each node with x and y added
 */
export function ringLayout(nodes, { center = 180, radius = 130 } = {}) {
  if (!Array.isArray(nodes) || nodes.length === 0) {
    return []
  }
  return nodes.map((node, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / nodes.length
    return { ...node, x: center + radius * Math.cos(angle), y: center + radius * Math.sin(angle) }
  })
}

/**
 * The newest election messages sent during one round, from the module's events: MESSAGE_SENT
 * events after that round's ELECTION_ROUND_STARTED and, once it finished, not after its
 * ELECTION_ROUND_FINISHED. Lamport values are the backend's, never computed here.
 *
 * @returns {Array<{sequence: number, from: number, to: number, type: string, lamportTime: number}>}
 */
export function roundMessages(events, round, limit = MAX_ARCS) {
  if (!round || !Array.isArray(events)) {
    return []
  }
  const ofRound = (type) => events.find((event) => event.type === type && event.data?.roundId === round.roundId)
  const started = ofRound('ELECTION_ROUND_STARTED')
  if (!started) {
    return []
  }
  const finished = ofRound('ELECTION_ROUND_FINISHED')
  return events
    .filter((event) => event.type === 'MESSAGE_SENT' && event.peerId != null
      && event.sequence > started.sequence && (!finished || event.sequence <= finished.sequence))
    .sort((a, b) => a.sequence - b.sequence)
    .slice(-limit)
    .map((event) => ({
      sequence: event.sequence,
      from: event.nodeId,
      to: event.peerId,
      type: event.data?.messageType ?? null,
      lamportTime: event.lamportTime,
    }))
}

/** Announcement when a round opens. */
export function describeRoundStart(round) {
  const algorithm = algorithmLabel(round.algorithm)
  if (round.trigger === 'LEADER_FAILURE') {
    return `The leader failed. A ${algorithm} election started from node ${round.initiatorNodeId}.`
  }
  if (round.trigger === 'RECOVERY') {
    return `Node ${round.initiatorNodeId} recovered and started a ${algorithm} election.`
  }
  return `${algorithm} election started from node ${round.initiatorNodeId}.`
}

/** Announcement when a round ends; the timeout text comes from the configured round timeout. */
export function describeRoundEnd(round, roundTimeoutMillis) {
  if (round.outcome === 'TIMED_OUT') {
    const seconds = secondsFrom(roundTimeoutMillis)
    return `The election timed out${seconds != null ? ` after ${seconds} s` : ''}: no leader was agreed.`
  }
  const millis = formatMillis(round.durationMillis)
  return `Election finished: node ${round.leaderId} was elected${millis != null ? ` in ${millis} ms` : ''}.`
}

/** Announcement when the cluster leader changes. */
export function describeLeader(leaderId) {
  return leaderId != null ? `Node ${leaderId} is now the leader.` : 'There is no leader now.'
}

/** One line for a LEADER_CHANGED event of the cluster log. */
export function leaderChangeText(event) {
  const leader = event?.data?.leaderId ?? null
  const previous = event?.data?.previousLeaderId ?? null
  if (leader != null) {
    return previous != null
      ? `Node ${leader} became the leader, after node ${previous}`
      : `Node ${leader} became the leader`
  }
  return previous != null ? `No leader: node ${previous} is no longer the leader` : 'No leader'
}
