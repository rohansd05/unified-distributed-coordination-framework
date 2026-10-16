import { CheckCircle2, HelpCircle, XCircle } from 'lucide-react'
import { OutcomeBadge } from '@/modules/mpi/CollectiveResult'
import { refusalLabel, rootSourceLabel, shortChecksum, transferStatusLabel } from '@/modules/mpi/labels'
import { clockTime, exactNanos, failureKindLabel, formatNanos } from '@/modules/rmi/labels'
import {
  blockRows,
  computeStatusLabel,
  figureRows,
  formatShare,
  formatSpeedup,
  glossaryEntry,
  kindLabel,
  nodeReasonLabel,
  stackModel,
} from './matrixModel'

/** A value the backend sent as null: "Not measured" or "Not available" in words, with the reason; never 0. */
export function Missing({ text = 'Not measured', reason }) {
  return (
    <span data-testid="missing" title={reason} className="text-muted-foreground">
      {text}{reason && <span className="sr-only"> ({reason})</span>}
    </span>
  )
}

function Nanos({ value, reason }) {
  const text = formatNanos(value)
  return text ? <span className="tabular-nums" title={exactNanos(value)}>{text}</span> : <Missing reason={reason} />
}

function Checksum({ value }) {
  if (typeof value !== 'string') return <Missing text="Not available" reason="No checksum: not every block arrived" />
  return <span title={value} className="font-mono text-xs">{shortChecksum(value)}<span className="sr-only"> (full checksum {value})</span></span>
}

function Kind({ kind }) {
  if (!kind) return null
  return <span className="ml-2 rounded border px-1 py-px text-[11px] text-muted-foreground">{kindLabel(kind)}</span>
}

function Row({ label, kind, children, note }) {
  return (
    <div className="grid gap-1 py-1.5 sm:grid-cols-[14rem_1fr] sm:gap-3">
      <dt className="text-muted-foreground">{label}<Kind kind={kind} /></dt>
      <dd className="min-w-0 break-words">
        {children}
        {note && <p className="mt-0.5 text-xs text-muted-foreground">{note}</p>}
      </dd>
    </div>
  )
}

function figureValue(row) {
  if (row.value === null) return <Missing />
  if (row.format === 'nanos') return <Nanos value={row.value} />
  if (row.format === 'share') return <span className="tabular-nums" title={String(row.value)}>{formatShare(row.value)}</span>
  return <span className="tabular-nums">{String(row.value)}</span>
}

const SEGMENT_STYLE = ['bg-primary', 'bg-primary/60', 'bg-success/70', 'bg-primary/30']

/** The four phases stacked, scaled to the larger of the parallel time and their sum; other time as a number beside it. */
function PhaseStack({ run }) {
  const model = stackModel(run)
  if (!model) return null
  return (
    <div data-testid="phase-stack" className="space-y-1.5">
      <p className="text-xs text-muted-foreground">Where the parallel time went (each part measured on the root)</p>
      <div className="flex h-4 w-full overflow-hidden rounded border" aria-hidden="true">
        {model.segments.map((segment, index) => (
          <div key={segment.id} className={SEGMENT_STYLE[index]} style={{ width: `${segment.fraction * 100}%` }} />
        ))}
      </div>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {model.segments.map((segment, index) => (
          <li key={segment.id} className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className={`inline-block size-2.5 rounded-sm ${SEGMENT_STYLE[index]}`} />
            {segment.label} <Nanos value={segment.nanos} />
          </li>
        ))}
        <li data-testid="stack-other">Other time, not in the bar: <Nanos value={model.otherNanos} reason="Not derived for this run" /></li>
      </ul>
    </div>
  )
}

function Verdict({ passed }) {
  if (passed === true) return <span className="inline-flex items-center gap-1 font-medium text-success"><CheckCircle2 aria-hidden="true" className="size-4" />Passed</span>
  if (passed === false) return <span className="inline-flex items-center gap-1 font-medium text-destructive"><XCircle aria-hidden="true" className="size-4" />Failed: the result does not match</span>
  return <span className="inline-flex items-center gap-1 text-muted-foreground"><HelpCircle aria-hidden="true" className="size-4" />Unknown: a block is missing, so the whole result could not be compared</span>
}

function fetchText(fetch) {
  if (!fetch) return <Missing text="Not available" reason="No fetch was recorded for this rank" />
  if (fetch.status === 'DELIVERED') return <Nanos value={fetch.latencyNanos} />
  if (fetch.status === 'LOCAL') return 'Local (root)'
  return transferStatusLabel(fetch.status)
}

function failureText(compute) {
  if (compute.status !== 'FAILED') return null
  const kind = compute.failureKind ? failureKindLabel(compute.failureKind) : null
  const reason = compute.nodeReason ? `the node said ${nodeReasonLabel(compute.nodeReason).toLowerCase()}` : null
  return [kind, reason].filter(Boolean).join(', ')
}

/** One row per rank: allocation, where, status, round trip, node compute time, fetch, and any reason. Holes stay holes. */
function NodeTable({ run }) {
  const rows = blockRows(run)
  return (
    <div className="space-y-2">
      <h4 className="text-sm font-semibold">Per node</h4>
      <div className="overflow-x-auto rounded-md border">
        <table className="w-full min-w-[46rem] text-left text-xs">
          <thead className="bg-muted/30 text-muted-foreground">
            <tr>
              <th scope="col" className="px-2 py-1.5 font-medium">Rank</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Node</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Rows (start, count)</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Where</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Compute</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Compute round trip</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Node compute time</th>
              <th scope="col" className="px-2 py-1.5 font-medium">Fetch of its block</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const { compute } = row
              const failure = failureText(compute)
              return [
                <tr key={row.key} data-testid="node-row" data-rank={row.rank} className="border-t">
                  <td className="px-2 py-1.5 font-mono">{row.rank}</td>
                  <td className="px-2 py-1.5 font-mono">{row.nodeId}{row.root ? ' (root)' : ''}</td>
                  <td className="px-2 py-1.5 tabular-nums">{row.rowStart}, {row.rowCount}</td>
                  <td className="px-2 py-1.5">{compute.local ? 'Local' : 'Remote'}</td>
                  <td className="px-2 py-1.5">{computeStatusLabel(compute.status)}{failure ? ` (${failure})` : ''}</td>
                  <td className="px-2 py-1.5">
                    {compute.local
                      ? <Missing text="Not available" reason="The root's own block is computed without a call" />
                      : <Nanos value={compute.roundTripNanos} reason="No reply came back" />}
                  </td>
                  <td className="px-2 py-1.5"><Nanos value={compute.computeNanos} reason="The node computed nothing" /></td>
                  <td className="px-2 py-1.5">{fetchText(row.fetch)}{row.fetch?.verified === false ? ', checksum differs' : ''}</td>
                </tr>,
                row.hole && (
                  <tr key={`${row.key}-hole`} data-testid="hole-row" className="bg-destructive/5">
                    <td colSpan={8} className="px-2 pb-1.5 text-foreground">
                      Hole: {row.hole}
                      {typeof compute.timeToFailureNanos === 'number' && (
                        <span className="text-muted-foreground"> Time to failure of the compute: <Nanos value={compute.timeToFailureNanos} /> (not a latency).</span>
                      )}
                      {typeof row.fetch?.timeToFailureNanos === 'number' && (
                        <span className="text-muted-foreground"> Time to failure of the fetch: <Nanos value={row.fetch.timeToFailureNanos} /> (not a latency).</span>
                      )}
                    </td>
                  </tr>
                ),
              ]
            })}
          </tbody>
        </table>
      </div>
      {run.computeRoundTripBasis && <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">Compute round trip.</span> {run.computeRoundTripBasis}</p>}
      {run.gatherLatencyBasis && <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">Fetch.</span> {run.gatherLatencyBasis}</p>}
    </div>
  )
}

/**
 * Everything measured and derived for one run, as the backend sent it: the outcome, the root and
 * why it was chosen, every timing figure with the served glossary's label, kind and definition,
 * the speedups (the measured one only from timing.derived.speedup, with its basis; the
 * compute-bound one labelled as such; the withheld reason when there is none), the phase bar,
 * the verification and one row per rank. A null is "Not measured" or "Not available", never 0.
 *
 * @param {object} props
 * @param {object} props.run the MatrixRunDto
 * @param {object|null} props.overview the MatrixOverviewDto (glossary, notes, processors)
 */
export function RunResult({ run, overview }) {
  const glossary = overview?.glossary
  const label = (id, fallback) => glossaryEntry(glossary, id)?.label ?? fallback
  const definition = (id) => glossaryEntry(glossary, id)?.definition ?? null
  const kind = (id) => glossaryEntry(glossary, id)?.kind ?? null
  const derived = run.timing?.derived
  const verification = run.verification
  return (
    <div data-testid="run-result" className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <OutcomeBadge outcome={run.outcome} />
        <span className="font-medium">Run <span className="font-mono">{run.runId}</span>, size {run.size}</span>
        {run.startedAt && <span className="font-mono text-xs text-muted-foreground">{clockTime(run.startedAt)}</span>}
      </div>
      <p>
        Root: node {run.rootNodeId}
        {typeof run.rootRank === 'number' ? `, rank ${run.rootRank}` : ''}, chosen as {rootSourceLabel(run.rootSource)}.
      </p>
      {run.outcome === 'REFUSED' ? (
        <div data-testid="refusal" className="rounded-md border bg-muted/20 p-3">
          <p className="font-medium">{refusalLabel(run.refusal?.reason)}</p>
          <p className="mt-1">{run.refusal?.message}</p>
          <p className="mt-1 text-muted-foreground">Nothing was split or sent, so this run has no ranks, phases, times or verification. It is kept in the history.</p>
        </div>
      ) : (
        <>
          <dl data-testid="speedup-block" className="divide-y rounded-md border px-3">
            <Row label={label('speedup', 'Speedup')} kind={kind('speedup')} note={run.speedupBasis}>
              {typeof derived?.speedup === 'number'
                ? <span data-testid="speedup" className="text-lg font-semibold tabular-nums" title={String(derived.speedup)}>{formatSpeedup(derived.speedup)}</span>
                : <span data-testid="speedup-withheld">{derived?.speedupWithheldReason ?? <Missing text="Not available" />}</span>}
            </Row>
            <Row label={`${label('computeBoundSpeedup', 'Compute-bound speedup')} (not the measured speedup)`} kind={kind('computeBoundSpeedup')}
              note={definition('computeBoundSpeedup')}>
              {typeof derived?.computeBoundSpeedup === 'number'
                ? <span data-testid="compute-bound" className="tabular-nums" title={String(derived.computeBoundSpeedup)}>{formatSpeedup(derived.computeBoundSpeedup)}</span>
                : <Missing text="Not available" reason={derived?.speedupWithheldReason ?? undefined} />}
            </Row>
            <Row label="Speedup ceiling" note="A bound, not a measurement: the smaller of the node count and the processor count; see the speedup basis.">
              {typeof derived?.speedupCeiling === 'number' ? <span className="tabular-nums">{derived.speedupCeiling}</span> : <Missing text="Not available" />}
            </Row>
            <Row label="Processors" note="As the JVM reports them now, from the module overview.">
              {typeof overview?.processors === 'number' ? <span className="tabular-nums">{overview.processors}</span> : <Missing text="Not available" />}
            </Row>
          </dl>
          <PhaseStack run={run} />
          <dl data-testid="figures" className="divide-y rounded-md border px-3">
            {figureRows(run, glossary).map((row) => (
              <Row key={row.id} label={row.label} kind={row.kind}
                note={[row.definition, row.id === 'scatterNanos' || row.id === 'broadcastNanos' ? overview?.phaseTimeLabel : null,
                  row.id === 'payloadGenerationNanos' ? overview?.payloadGenerationNote : null].filter(Boolean).join(' ')}>
                <span data-testid={`figure-${row.id}`}>{figureValue(row)}</span>
              </Row>
            ))}
          </dl>
          {overview?.firstRunNote && <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">First run.</span> {overview.firstRunNote}</p>}
          <div className="space-y-1" data-testid="verification">
            <h4 className="text-sm font-semibold">Verification against the sequential product</h4>
            {verification ? (
              <dl className="divide-y rounded-md border px-3">
                <Row label="Result"><Verdict passed={verification.passed} /></Row>
                <Row label="Rows received">{verification.rowsReceived} of {verification.rowsExpected}</Row>
                <Row label="Blocks missing">{verification.blocksMissing}</Row>
                <Row label="Blocks with a bad checksum">{verification.blocksWithBadChecksum}</Row>
                <Row label="Elements compared">{verification.elementsCompared}</Row>
                <Row label="Mismatched elements">{verification.mismatchedElements}</Row>
                <Row label="Parallel checksum"><Checksum value={verification.parallelChecksum} /></Row>
                <Row label="Sequential checksum"><Checksum value={verification.sequentialChecksum} /></Row>
                <Row label="Checksums match">
                  {verification.checksumsMatch === null ? <Missing text="Not available" reason="Not compared: a block is missing" /> : verification.checksumsMatch ? 'Yes' : 'No'}
                </Row>
              </dl>
            ) : <Missing text="Not available" reason="Nothing was assembled" />}
          </div>
          <NodeTable run={run} />
        </>
      )}
    </div>
  )
}
