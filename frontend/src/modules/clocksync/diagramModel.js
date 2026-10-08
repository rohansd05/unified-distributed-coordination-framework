/**
 * Geometric and causal layout model for the space-time diagram (Experiment 3).
 *
 * Implements pure layout math:
 * - One horizontal lane per node.
 * - Monotonic event placement with minimum step spacing for horizontal scrolling.
 * - Message pairing between SEND and RECV events by messageId.
 * - Violation tagging matching the backend's verification.violations by (nodeId, lamportTime).
 * - Total ordering of events by (lamportTime, nodeId) for keyboard-accessible table view.
 */

export const MAX_DISPLAYED_EVENTS = 40

/**
 * Sorts events according to Lamport's total order:
 * Primary: lamportTime ascending.
 * Secondary: nodeId ascending.
 */
export function sortEventsTotalOrder(events) {
  if (!Array.isArray(events)) return []
  return [...events].sort((a, b) => {
    if (a.lamportTime !== b.lamportTime) {
      return a.lamportTime - b.lamportTime
    }
    return a.nodeId - b.nodeId
  })
}

/**
 * Pairs SEND and RECV events within the displayed slice by messageId.
 * A RECV whose SEND was dropped or is outside the slice is drawn without an arrow.
 */
export function pairMessages(events) {
  if (!Array.isArray(events)) return []
  const sends = new Map()
  for (const event of events) {
    if (event.type === 'SEND' && event.messageId != null) {
      sends.set(event.messageId, event)
    }
  }

  const pairs = []
  for (const event of events) {
    if (event.type === 'RECV' && event.messageId != null) {
      const sendEvent = sends.get(event.messageId)
      if (sendEvent) {
        pairs.push({
          messageId: event.messageId,
          sendEvent,
          recvEvent: event,
        })
      }
    }
  }
  return pairs
}

/**
 * Builds the geometric diagram model for SVG rendering.
 *
 * @param {object} options
 * @param {Array} options.nodes cluster nodes
 * @param {Array} options.events timeline events from backend
 * @param {Array} [options.violations] causal violations from backend verification
 * @param {number} [options.laneHeight=64] height of each node lane in px
 * @param {number} [options.minEventSpacing=48] minimum spacing between event columns
 */
export function buildDiagramModel({
  nodes = [],
  events = [],
  violations = [],
  laneHeight = 64,
  minEventSpacing = 48,
}) {
  const sortedNodes = [...nodes].sort((a, b) => a.nodeId - b.nodeId)
  const padding = { top: 36, bottom: 36, left: 96, right: 48 }

  const lanes = sortedNodes.map((node, index) => ({
    nodeId: node.nodeId,
    status: node.status,
    y: padding.top + index * laneHeight,
    height: laneHeight,
  }))

  const laneMap = new Map(lanes.map((lane) => [lane.nodeId, lane]))

  // Slice to bounded latest N events
  const rawDisplayed = (events || []).slice(-MAX_DISPLAYED_EVENTS)

  // Violation lookup by (nodeId, lamportTime) as specified
  const violationMap = new Map()
  if (Array.isArray(violations)) {
    for (const v of violations) {
      const key = `${v.nodeId}-${v.actualLamportTime}`
      violationMap.set(key, v)
    }
  }

  // Pre-calculate message pairings to know which receives have their send present
  const pairs = pairMessages(rawDisplayed)
  const pairedMessageIds = new Set(pairs.map((p) => p.messageId))

  const totalEvents = rawDisplayed.length
  const plotWidth = Math.max(480, totalEvents * minEventSpacing)
  const totalSvgWidth = padding.left + plotWidth + padding.right
  const totalSvgHeight = padding.top + Math.max(1, lanes.length) * laneHeight + padding.bottom

  const positionedEvents = rawDisplayed.map((event, index) => {
    const lane = laneMap.get(event.nodeId)
    const x = padding.left + (totalEvents <= 1 ? plotWidth / 2 : (index / (totalEvents - 1)) * plotWidth)
    const y = lane ? lane.y : padding.top

    // Violation matching:
    // As instructed: a Receive whose Send was dropped is never a violation.
    const key = `${event.nodeId}-${event.lamportTime}`
    const violation = violationMap.get(key)
    const sendIsDropped = event.type === 'RECV' && (!pairedMessageIds.has(event.messageId))
    const isViolation = Boolean(violation && !sendIsDropped)

    return {
      ...event,
      index,
      x,
      y,
      isViolation,
      violationDetail: isViolation ? violation.message : null,
    }
  })

  // Map send events by messageId for pairing
  const sendByMsgId = new Map()
  for (const e of positionedEvents) {
    if (e.type === 'SEND' && e.messageId != null) {
      sendByMsgId.set(e.messageId, e)
    }
  }

  const arrows = []
  for (const e of positionedEvents) {
    if (e.type === 'RECV' && e.messageId != null) {
      const sendEvent = sendByMsgId.get(e.messageId)
      if (sendEvent) {
        arrows.push({
          messageId: e.messageId,
          fromNodeId: sendEvent.nodeId,
          toNodeId: e.nodeId,
          x1: sendEvent.x,
          y1: sendEvent.y,
          x2: e.x,
          y2: e.y,
          isViolation: e.isViolation,
          lamportSend: sendEvent.lamportTime,
          lamportRecv: e.lamportTime,
        })
      }
    }
  }

  return {
    lanes,
    events: positionedEvents,
    arrows,
    width: totalSvgWidth,
    height: totalSvgHeight,
    totalCount: (events || []).length,
    displayedCount: positionedEvents.length,
  }
}
