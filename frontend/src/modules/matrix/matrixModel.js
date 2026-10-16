import { failureKindLabel, formatNanos } from '@/modules/rmi/labels'

/**
 * Pure view models for the Experiment 10 page: no React, no network. Every figure is the
 * backend's, as it was sent; nothing here measures, clamps or invents a number, and every limit
 * (sizes, series sizes, timeouts) is read from the overview. No matrix values exist on the wire
 * (only checksums), so none are shown.
 */

function sentenceCase(value) {
  if (!value) return ''
  const words = String(value).toLowerCase().split('_').join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const COMPUTE_STATUSES = { DONE: 'Done', FAILED: 'Failed', NOT_ATTEMPTED: 'Not attempted' }
const KINDS = { MEASURED_ON_ROOT: 'Measured on the root', MEASURED_ON_NODE: 'Measured on the node', DERIVED: 'Derived' }

export const computeStatusLabel = (status) => COMPUTE_STATUSES[status] ?? sentenceCase(status)
/** "Input missing", "Compute timed out": a node's own reason, in sentence case. */
export const nodeReasonLabel = (reason) => sentenceCase(reason)
/** The served glossary kind in words. */
export const kindLabel = (kind) => KINDS[kind] ?? sentenceCase(kind)

/** A speedup with three significant digits ("0.00491", "0.223", "4.99"), never rounded to 0; null when not a number. */
export function formatSpeedup(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value.toPrecision(3) : null
}

/** The served communication share as a percentage with one decimal ("72.3 %"); null when not a number. */
export function formatShare(share) {
  return typeof share === 'number' && Number.isFinite(share) ? `${(share * 100).toFixed(1)} %` : null
}

const participantsOf = (overview) => (Array.isArray(overview?.participants) ? overview.participants : [])

/**
 * True when some node is up but does not export its MatrixEndpoint (a fresh backend, a failed
 * start, or after a cluster reset). A crashed node does not count: it cannot be started.
 */
export function needsStart(overview) {
  return participantsOf(overview).some((node) => node.nodeStatus === 'UP' && node.matrixEndpointExported === false)
}

/** True when the overview lists nodes and every one of them is crashed. */
export function allNodesDown(overview) {
  const nodes = participantsOf(overview)
  return nodes.length > 0 && nodes.every((node) => node.nodeStatus === 'CRASHED')
}

const isEmpty = (value) => value === undefined || value === null || String(value).trim() === ''

/** The body of POST /runs: rootNode only when chosen (empty means the backend's default). No seed: the run id is used. */
export function runBody({ size, rootNode }) {
  const body = { size: Number(size) }
  if (!isEmpty(rootNode)) body.rootNode = Number(rootNode)
  return body
}

/**
 * The size check the page makes before sending, in the backend's own words, with the bounds from
 * the served limits (minSize to maxSize). A size below minimumSizeNow is NOT refused here: the
 * backend answers it with a recorded REFUSED run, which is part of the demonstration.
 *
 * @returns {Record<string, string>} field name to message; empty when the form can be sent
 */
export function validateRun(form, overview) {
  const errors = {}
  const min = overview?.limits?.minSize
  const max = overview?.limits?.maxSize
  if (isEmpty(form.size)) {
    errors.size = 'size is required'
  } else {
    const size = Number(form.size)
    if (!Number.isSafeInteger(size) || (typeof min === 'number' && size < min) || (typeof max === 'number' && size > max)) {
      errors.size = `size must be ${min}-${max}, was ${String(form.size).trim()}`
    }
  }
  return errors
}

/**
 * The overview as Experiment 9's CollectiveMap reads it: a node's endpoint here is MatrixEndpoint,
 * and coHosting is left out because the page shows its one Simulated badge elsewhere.
 */
export function mapOverviewForMap(overview) {
  if (!overview) return null
  return {
    ...overview,
    coHosting: null,
    participants: participantsOf(overview).map((node) => ({ ...node, collectiveEndpointExported: node.matrixEndpointExported })),
  }
}

/**
 * The run's scatter or broadcast as the CollectiveDto shape that CollectiveMap and chartModel
 * read. A MatrixPhaseDto has no id, start time, root or skipped nodes of its own: they come from
 * its collectiveId and the run. null when the run has no such phase (REFUSED).
 *
 * @param {'scatter'|'broadcast'} key
 */
export function phaseAsCollective(run, key) {
  const phase = run?.[key]
  if (!phase) return null
  return { ...phase, id: phase.collectiveId, startedAt: run.startedAt, rootNodeId: run.rootNodeId, skippedNodeIds: run.skippedNodeIds }
}

/** The gather of C as a transfer list for chartModel; null when nothing was gathered. */
export function gatherAsTransfers(run) {
  return Array.isArray(run?.gather) && run.gather.length > 0 ? { transfers: run.gather } : null
}

const arrived = (fetch) => Boolean(fetch) && (fetch.status === 'LOCAL' || fetch.status === 'DELIVERED')

/** Why a rank has no block of C, from the compute and the fetch as they were sent; null when the block arrived. */
function holeReason(compute, fetch) {
  if (arrived(fetch)) return null
  if (compute?.status === 'FAILED') {
    const what = compute.nodeReason ? nodeReasonLabel(compute.nodeReason) : failureKindLabel(compute.failureKind)
    const message = compute.nodeMessage ?? compute.failureMessage
    return `Compute failed: ${what}${message ? `. ${message}` : ''}`
  }
  if (compute?.status === 'NOT_ATTEMPTED') return `Compute not attempted. ${compute.notAttemptedReason ?? ''}`.trim()
  if (fetch?.status === 'FAILED') {
    return `Fetch failed: ${failureKindLabel(fetch.failureKind)}${fetch.failureMessage ? `. ${fetch.failureMessage}` : ''}`
  }
  if (fetch?.status === 'NOT_ATTEMPTED') return `Fetch not attempted. ${fetch.notAttemptedReason ?? ''}`.trim()
  return 'No block arrived'
}

/**
 * One row per rank, in rank order: the row allocation (rowStart, rowCount), where it was computed,
 * the compute and the fetch of its block as sent, and whether the block of C arrived. A rank whose
 * block did not arrive is a hole with its reason, never filled in. Empty for a REFUSED run.
 */
export function blockRows(run) {
  const computes = Array.isArray(run?.computes) ? run.computes : []
  const gather = Array.isArray(run?.gather) ? run.gather : []
  return computes.map((compute) => {
    const fetch = gather.find((transfer) => transfer.rank === compute.rank) ?? null
    return {
      key: `rank-${compute.rank}`,
      rank: compute.rank,
      nodeId: compute.nodeId,
      rowStart: compute.rowStart,
      rowCount: compute.rowCount,
      root: compute.nodeId === run.rootNodeId,
      compute,
      fetch,
      arrived: arrived(fetch),
      hole: holeReason(compute, fetch),
    }
  })
}

/**
 * The run's figures in reading order, each with the served glossary's label, kind and
 * definition. A figure not in the glossary keeps its id as its label. value is exactly what was
 * sent (null stays null).
 */
const FIGURES = [
  { id: 'sequentialNanos', from: 'measured', format: 'nanos' },
  { id: 'parallelTotalNanos', from: 'measured', format: 'nanos' },
  { id: 'scatterNanos', from: 'measured', format: 'nanos' },
  { id: 'broadcastNanos', from: 'measured', format: 'nanos' },
  { id: 'computePhaseNanos', from: 'measured', format: 'nanos' },
  { id: 'gatherNanos', from: 'measured', format: 'nanos' },
  { id: 'communicationNanos', from: 'derived', format: 'nanos' },
  { id: 'communicationShare', from: 'derived', format: 'share' },
  { id: 'otherNanos', from: 'derived', format: 'nanos' },
  { id: 'payloadGenerationNanos', from: 'measured', format: 'nanos' },
  { id: 'multiplyAdds', from: 'derived', format: 'count' },
]

export function figureRows(run, glossary) {
  const entries = Array.isArray(glossary) ? glossary : []
  return FIGURES.map(({ id, from, format }) => {
    const entry = entries.find((item) => item.id === id)
    return {
      id,
      format,
      label: entry?.label ?? id,
      kind: entry?.kind ?? null,
      definition: entry?.definition ?? null,
      value: run?.timing?.[from]?.[id] ?? null,
    }
  })
}

/** The served glossary entry for an id, or null. */
export function glossaryEntry(glossary, id) {
  return (Array.isArray(glossary) ? glossary : []).find((item) => item.id === id) ?? null
}

const PHASES = [
  { id: 'scatterNanos', label: 'Scatter' },
  { id: 'broadcastNanos', label: 'Broadcast' },
  { id: 'computePhaseNanos', label: 'Compute' },
  { id: 'gatherNanos', label: 'Gather' },
]

/**
 * The stacked bar of the four phases, scaled to the larger of the parallel total and the sum of
 * the phases (so nothing is cut off). null when any phase or the total is null. The other time is
 * the served otherNanos, shown beside the bar as it is (it can be negative).
 */
export function stackModel(run) {
  const measured = run?.timing?.measured
  const total = measured?.parallelTotalNanos
  if (typeof total !== 'number' || PHASES.some((phase) => typeof measured[phase.id] !== 'number')) return null
  const sum = PHASES.reduce((acc, phase) => acc + measured[phase.id], 0)
  const scale = Math.max(total, sum)
  return {
    segments: PHASES.map((phase) => ({ ...phase, nanos: measured[phase.id], fraction: scale > 0 ? measured[phase.id] / scale : 0 })),
    total,
    otherNanos: run.timing.derived?.otherNanos ?? null,
  }
}

/**
 * The speedup chart: one slot per configured series size, in the served order. A size with a
 * point carries its served speedup and rank count; a size without one is a gap with the newest
 * run of that size (or none), never a 0. top is the axis maximum: the largest of 1, the served
 * ceiling and the largest point, so no mark is ever clipped.
 */
export function seriesModel(series, overview) {
  const sizes = Array.isArray(series?.seriesSizes) ? series.seriesSizes : []
  const points = Array.isArray(series?.points) ? series.points : []
  const gaps = Array.isArray(series?.gaps) ? series.gaps : []
  const ceiling = typeof overview?.speedupCeiling === 'number' ? overview.speedupCeiling : null
  const slots = sizes.map((size) => {
    const point = points.find((item) => item.size === size)
    if (point && typeof point.speedup === 'number') {
      return { key: `size-${size}`, size, kind: 'point', speedup: point.speedup, ranks: point.ranks, runId: point.sourceRunId }
    }
    const gap = gaps.find((item) => item.size === size)
    return { key: `size-${size}`, size, kind: 'gap', runId: gap?.newestRunId ?? null, outcome: gap?.newestOutcome ?? null }
  })
  const speedups = slots.filter((slot) => slot.kind === 'point').map((slot) => slot.speedup)
  return { slots, ceiling, top: Math.max(1, ceiling ?? 0, ...speedups) }
}

/**
 * Rows for the history table, newest first as the backend sent them. Ids restart after a cluster
 * reset, so a row is keyed by id and start time, never by id alone.
 */
export function historyRows(runs) {
  return (Array.isArray(runs) ? runs : []).map((run) => ({
    key: `${run.runId}-${run.startedAt}`,
    runId: run.runId,
    startedAt: run.startedAt ?? null,
    size: run.size,
    rootNodeId: run.rootNodeId,
    outcome: run.outcome,
    parallelTotalNanos: run.timing?.measured?.parallelTotalNanos ?? null,
    passed: run.verification?.passed ?? null,
    run,
  }))
}

const SHARED_TEXT = 'Every node runs on the same computer, so the parallel run can never be faster than the smaller of the '
  + 'node count and the processor count allows, and the scatter, the broadcast and the gather are paid on top of the '
  + 'multiply they split up.'
const MATCH_TEXT = 'A run counts only if its assembled result matches the sequential product bit for bit. A partial, failed '
  + 'or mismatched run gets no speedup at all: the page shows why it was withheld instead of an estimate.'

/**
 * The "What to notice" callouts: two general sentences without figures, and a third about the
 * newest complete run in the history, only when it has a served communication share.
 */
export function noticeModel(runs) {
  const callouts = [SHARED_TEXT, MATCH_TEXT]
  const latest = (Array.isArray(runs) ? runs : []).find((run) => run.outcome === 'COMPLETE')
  const share = latest?.timing?.derived?.communicationShare
  if (typeof share === 'number') {
    const comm = formatNanos(latest.timing.derived.communicationNanos)
    callouts.push(`In this run (run ${latest.runId}, size ${latest.size}), communication took ${formatShare(share)} of the `
      + `parallel time${comm ? ` (${comm})` : ''}${share > 0.5 ? ': more than half, so communication dominated this run' : ''}.`)
  }
  return callouts
}
