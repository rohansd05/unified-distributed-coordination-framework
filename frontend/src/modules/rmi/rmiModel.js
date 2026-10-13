import { failureKindLabel, formatNanos, formatRatio, methodLabel, stageLabel } from './labels'

/**
 * Pure view models for the Experiment 1 page: no React, no network. Every figure comes from the
 * backend as it was sent; nothing here measures, clamps or invents a number.
 */

/** True when some node is up but its RMI endpoint is not started (a fresh backend, or after a cluster reset). */
export function needsStart(overview) {
  return Array.isArray(overview?.nodes)
    && overview.nodes.some((node) => node.nodeStatus === 'UP' && node.state === 'NOT_STARTED')
}

/** The parameter definition the overview gives for a method ("text" of ECHO, "rounds" of COMPUTE_HASH), or null. */
export function parameterOf(overview, method, name) {
  const entry = Array.isArray(overview?.methods) ? overview.methods.find((m) => m.method === method) : null
  return entry?.parameters?.find((parameter) => parameter.name === name) ?? null
}

/** The request body for POST /calls and /comparisons: text only for ECHO, rounds only for COMPUTE_HASH. */
export function callBody({ fromNode, targetNode, method, text, rounds }) {
  const body = { fromNode: Number(fromNode), targetNode: Number(targetNode), method }
  if (method === 'ECHO') body.text = text ?? ''
  if (method === 'COMPUTE_HASH') body.rounds = Number(rounds)
  return body
}

/**
 * The checks the page can make before sending, with every limit taken from the overview. The
 * backend checks again; its 400 is shown beside the same fields.
 *
 * @returns {Record<string, string>} field name to message; empty when the form can be sent
 */
export function validateCall(form, overview) {
  const errors = {}
  const nodes = Array.isArray(overview?.nodes) ? overview.nodes : []
  if (form.fromNode === '' || form.fromNode == null) {
    errors.fromNode = 'Choose the calling node.'
  } else if (nodes.find((node) => node.nodeId === Number(form.fromNode))?.nodeStatus === 'CRASHED') {
    errors.fromNode = `Node ${form.fromNode} is down: a crashed node cannot call.`
  }
  if (form.targetNode === '' || form.targetNode == null) errors.targetNode = 'Choose the target node.'
  if (!form.method) errors.method = 'Choose a method.'
  if (form.method === 'ECHO') {
    const max = parameterOf(overview, 'ECHO', 'text')?.maximum ?? overview?.limits?.maxEchoLength
    const length = (form.text ?? '').length
    if (typeof max === 'number' && length > max) errors.text = `The text must be at most ${max} characters; it has ${length}.`
  }
  if (form.method === 'COMPUTE_HASH') {
    const parameter = parameterOf(overview, 'COMPUTE_HASH', 'rounds')
    const min = parameter?.minimum ?? 1
    const max = parameter?.maximum ?? overview?.limits?.maxHashRounds
    const rounds = Number(form.rounds)
    if (form.rounds === '' || !Number.isInteger(rounds) || rounds < min || (typeof max === 'number' && rounds > max)) {
      errors.rounds = `Rounds must be a whole number from ${min} to ${max}.`
    }
  }
  return errors
}

/** The call a history entry shows on the map and chart: a CALL itself, or the remote half of a COMPARISON. */
export function latestCall(entry) {
  if (!entry) return null
  if (entry.kind === 'COMPARISON') return entry.comparison?.remote ?? entry.comparison?.local ?? null
  return entry.call ?? null
}

export const MAP_SIZE = { width: 360, height: 270 }
const MAP_RADIUS = 100

/**
 * Node positions on a circle (node 1 at the top, clockwise; the count comes from the overview)
 * and the arrow of the last call: success or failure with its kind, or a loop for a self-call.
 */
export function mapModel(overview, call) {
  const list = Array.isArray(overview?.nodes) ? overview.nodes : []
  const cx = MAP_SIZE.width / 2
  const cy = MAP_SIZE.height / 2
  const nodes = list.map((node, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / Math.max(1, list.length)
    let status = 'up'
    if (node.nodeStatus === 'CRASHED') status = 'down'
    else if (node.state === 'NOT_STARTED') status = 'not-started'
    return {
      id: node.nodeId,
      x: Math.round(cx + MAP_RADIUS * Math.cos(angle)),
      y: Math.round(cy + MAP_RADIUS * Math.sin(angle)),
      status,
      state: node.state,
      port: node.registryPort,
    }
  })
  let arrow = null
  if (call && nodes.some((n) => n.id === call.fromNode) && nodes.some((n) => n.id === call.targetNode)) {
    arrow = {
      from: call.fromNode,
      to: call.targetNode,
      self: call.fromNode === call.targetNode,
      ok: call.outcome === 'SUCCESS',
      kind: call.failureKind ?? null,
      method: call.method,
      path: call.path,
    }
  }
  return { nodes, arrow }
}

/** One sentence for the last call, shown under the map. */
export function callSentence(call) {
  if (!call) return 'No call yet: start with a ping.'
  const what = `${methodLabel(call.method)} from node ${call.fromNode} to node ${call.targetNode}`
  if (call.outcome === 'SUCCESS') {
    const time = formatNanos(call.roundTripNanos)
    return `Last call: ${what} succeeded${time ? `, round trip ${time}` : ''}.`
  }
  const time = formatNanos(call.timeToFailureNanos)
  return `Last call: ${what} failed at the ${stageLabel(call.failureStage)} with ${failureKindLabel(call.failureKind).toLowerCase()}`
    + `${time ? ` after ${time}` : ''}.`
}

/** The REMOTE calls in a history (newest first), oldest first, each CALL plus each comparison's remote half. */
function remoteCallsOldestFirst(history) {
  const list = Array.isArray(history) ? history : []
  return list
    .map((entry) => (entry.kind === 'COMPARISON' ? entry.comparison?.remote : entry.call))
    .filter((call) => call && call.path === 'REMOTE')
    .reverse()
}

export const CHART_LIMIT = 12

/**
 * The last remote calls for the round-trip chart, oldest first. A completed call gets a bar of
 * its measured round trip; a failed one gets a failure marker, never a zero-height bar.
 */
export function chartModel(history, limit = CHART_LIMIT) {
  const calls = remoteCallsOldestFirst(history).slice(-limit)
  const bars = calls.map((call, index) => ({
    key: `${index}-${call.id}-${call.calledAt}`,
    id: call.id,
    method: call.method,
    targetNode: call.targetNode,
    ok: call.outcome === 'SUCCESS' && typeof call.roundTripNanos === 'number',
    nanos: call.outcome === 'SUCCESS' ? call.roundTripNanos : null,
    failureKind: call.failureKind ?? null,
    timeToFailureNanos: call.timeToFailureNanos ?? null,
  }))
  const measured = bars.filter((bar) => bar.ok).map((bar) => bar.nanos)
  return { bars, max: measured.length > 0 ? Math.max(...measured) : null }
}

/**
 * Rows for the history table, newest first as the backend sent them. A cluster reset restarts
 * ids, so a row is keyed by kind, id and time, never by id alone.
 */
export function historyRows(history) {
  const list = Array.isArray(history) ? history : []
  return list.map((entry) => {
    const comparison = entry.kind === 'COMPARISON' ? entry.comparison : null
    const call = comparison ? null : entry.call
    const source = comparison ?? call ?? {}
    return {
      key: `${entry.kind}-${entry.id}-${source.calledAt}`,
      id: entry.id,
      kind: entry.kind,
      calledAt: source.calledAt ?? null,
      method: source.method,
      fromNode: source.fromNode,
      targetNode: source.targetNode,
      ok: comparison ? comparison.remote?.outcome === 'SUCCESS' : call?.outcome === 'SUCCESS',
      failureKind: comparison ? comparison.remote?.failureKind ?? null : call?.failureKind ?? null,
      nanos: comparison ? comparison.remoteNanos : call?.roundTripNanos ?? null,
      localNanos: comparison ? comparison.localNanos : null,
    }
  })
}

const FIRST_CALL_TEXT = 'Every remote call first looks the target up in that node\'s registry. The first call to a node is usually '
  + 'slower than later ones, because it also opens the connection and makes RMI\'s distributed garbage collection call.'
const CRASH_TEXT = 'Crash a node and call it: the call fails with a real RemoteException, showing the stage and the kind of '
  + 'failure, instead of hanging, because nothing accepts the connection.'
const OVERHEAD_TEXT = 'The overhead of a remote call over a direct one can be small, or even negative: each side is a single '
  + 'measurement, and local timing has noise too.'

/**
 * The earliest and the latest kept successful remote CALL for one target node and one method,
 * when the history holds at least two with both a lookup and a round trip. The group of the most
 * recent such call wins. Comparisons are left out: their remote half follows a direct call.
 */
function firstAndLatest(history) {
  const calls = (Array.isArray(history) ? history : [])
    .filter((entry) => entry.kind === 'CALL')
    .map((entry) => entry.call)
    .filter((call) => call && call.path === 'REMOTE' && call.outcome === 'SUCCESS'
      && typeof call.lookupNanos === 'number' && typeof call.roundTripNanos === 'number')
  for (const latest of calls) {   // newest first
    const group = calls.filter((call) => call.targetNode === latest.targetNode && call.method === latest.method)
    if (group.length >= 2) {
      return { earliest: group[group.length - 1], latest: group[0] }
    }
  }
  return null
}

/**
 * The three "What to notice" callouts. Each is a hedged general sentence; a measured figure is
 * added only when the history really holds it. The first callout compares two calls only when
 * there are two successful calls to the same node with the same method.
 */
export function noticeModel(history) {
  const list = Array.isArray(history) ? history : []
  const callouts = []

  const pair = firstAndLatest(list)
  if (pair) {
    const { earliest, latest } = pair
    callouts.push(`${FIRST_CALL_TEXT} Measured here: the earliest kept ${methodLabel(earliest.method)} to node `
      + `${earliest.targetNode} took ${formatNanos(earliest.lookupNanos)} to look up and ${formatNanos(earliest.roundTripNanos)} `
      + `for the call; the latest took ${formatNanos(latest.lookupNanos)} and ${formatNanos(latest.roundTripNanos)}.`)
  } else {
    callouts.push(FIRST_CALL_TEXT)
  }

  const failed = list
    .map((entry) => (entry.kind === 'CALL' ? entry.call : null))
    .find((call) => call?.outcome === 'REMOTE_FAILURE')
  if (failed) {
    const time = formatNanos(failed.timeToFailureNanos)
    callouts.push(`${CRASH_TEXT} Measured here: the ${methodLabel(failed.method)} to node ${failed.targetNode} failed at the `
      + `${stageLabel(failed.failureStage)} with ${failureKindLabel(failed.failureKind).toLowerCase()}`
      + `${time ? ` after ${time}` : ''}.`)
  } else {
    callouts.push(CRASH_TEXT)
  }

  const comparison = list.find((entry) => entry.kind === 'COMPARISON')?.comparison
  const overhead = formatNanos(comparison?.overheadNanos)
  if (comparison && overhead) {
    const ratio = formatRatio(comparison.ratio)
    callouts.push(`${OVERHEAD_TEXT} Latest comparison: ${methodLabel(comparison.method)} on node ${comparison.targetNode}, `
      + `overhead ${overhead}${ratio ? `, ratio ${ratio}` : ''}, as measured.`)
  } else {
    callouts.push(OVERHEAD_TEXT)
  }
  return callouts
}
