import { AlertTriangle, CheckCircle2, MinusCircle, XCircle } from 'lucide-react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { exactNanos, formatNanos } from '@/modules/rmi/labels'
import { outcomeLabel } from '@/modules/mpi/labels'
import { blockRows, computeStatusLabel } from './matrixModel'

const HATCH = { backgroundImage: 'repeating-linear-gradient(135deg, hsl(var(--destructive) / 0.25) 0 6px, transparent 6px 12px)' }

/** The state of a band as an icon plus a word (colour is never the only signal). */
function BandState({ row }) {
  if (!row.arrived) {
    return <span className="inline-flex items-center gap-1 font-medium text-destructive"><XCircle aria-hidden="true" className="size-3.5" />Hole</span>
  }
  if (row.fetch?.verified === false) {
    return <span className="inline-flex items-center gap-1 font-medium text-warning"><AlertTriangle aria-hidden="true" className="size-3.5" />Arrived, checksum differs</span>
  }
  return <span className="inline-flex items-center gap-1 font-medium text-success"><CheckCircle2 aria-hidden="true" className="size-3.5" />Arrived</span>
}

function Band({ row }) {
  const { compute } = row
  const last = row.rowStart + row.rowCount - 1
  const time = formatNanos(compute.computeNanos)
  return (
    <li data-testid="row-band" data-rank={row.rank} data-hole={row.arrived ? 'false' : 'true'}
      style={{ flexGrow: row.rowCount, flexBasis: 0, ...(row.arrived ? {} : HATCH) }}
      className={`flex min-h-[3.25rem] flex-col justify-center gap-0.5 rounded-md border px-3 py-1.5 text-xs ${row.arrived ? 'border-success/40 bg-success/10' : 'border-destructive/60'}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <span className="font-semibold text-foreground">
          Rank {row.rank}, node {row.nodeId}{row.root ? ' (root)' : ''}
        </span>
        <BandState row={row} />
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-muted-foreground">
        <span>Rows {row.rowStart} to {last} ({row.rowCount} {row.rowCount === 1 ? 'row' : 'rows'})</span>
        <span>{compute.local ? 'Computed on the root' : 'Computed remotely'}: {computeStatusLabel(compute.status).toLowerCase()}</span>
        {time && <span title={exactNanos(compute.computeNanos)}>node compute {time}</span>}
      </div>
      {row.hole && <p className="text-foreground">{row.hole}</p>}
    </li>
  )
}

/**
 * Experiment 10's bold element: the product C drawn as one band per rank, each as tall as its
 * share of the rows, with the rows it covers, where it was computed, the node's own compute time
 * and whether its block arrived. A block that did not arrive is a hatched hole with its reason,
 * never filled in. Before any run (or for a refused run) it lists the nodes instead. The
 * co-hosting fact carries the page's only Simulated badge, shown whenever this renders.
 *
 * @param {object} props
 * @param {object|null} props.overview the MatrixOverviewDto
 * @param {object|null} props.run the MatrixRunDto to draw
 */
export function RowBlocks({ overview, run }) {
  const rows = blockRows(run)
  const coHosting = overview?.coHosting
  const participants = Array.isArray(overview?.participants) ? overview.participants : []
  const skipped = Array.isArray(run?.skippedNodeIds) ? run.skippedNodeIds : []
  return (
    <div data-testid="row-blocks" className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">Rows of C, by rank</h3>
        {coHosting && <SimulatedBadge reason={coHosting.reason} />}
      </div>
      {rows.length > 0 ? (
        <>
          <p className="text-sm" data-testid="row-blocks-sentence">
            Run {run.runId}, size {run.size}: {outcomeLabel(run.outcome).toLowerCase()}.
            {' '}{rows.filter((row) => row.arrived).length} of {rows.length} blocks of C arrived.
          </p>
          <ol aria-label={`Row blocks of run ${run.runId}`} className="flex min-h-80 flex-col gap-1.5">
            {rows.map((row) => <Band key={row.key} row={row} />)}
          </ol>
        </>
      ) : (
        <div className="space-y-2 rounded-lg border bg-card p-4 text-sm">
          <p data-testid="row-blocks-sentence">
            {run?.outcome === 'REFUSED'
              ? `Run ${run.runId} was refused, so no rows were split.`
              : 'No run yet. A run splits the rows of A into one block per live node, in node order.'}
          </p>
          {participants.length > 0 && (
            <ul className="flex flex-wrap gap-2">
              {participants.map((node) => (
                <li key={node.nodeId} className="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-xs">
                  {node.nodeStatus === 'CRASHED'
                    ? <XCircle aria-hidden="true" className="size-3.5 text-destructive" />
                    : <MinusCircle aria-hidden="true" className="size-3.5 text-muted-foreground" />}
                  Node {node.nodeId}: {node.nodeStatus === 'CRASHED' ? 'down' : 'up'}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {skipped.length > 0 && (
        <p className="text-xs text-muted-foreground" data-testid="skipped-nodes">
          Skipped, because {skipped.length === 1 ? 'it was' : 'they were'} down when the run started: node{skipped.length === 1 ? '' : 's'} {skipped.join(', ')}.
        </p>
      )}
    </div>
  )
}
