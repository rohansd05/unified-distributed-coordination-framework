import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { CollectiveMap } from '@/modules/mpi/CollectiveMap'
import { OutcomeBadge } from '@/modules/mpi/CollectiveResult'
import { LatencyChart } from '@/modules/mpi/LatencyChart'
import { chartModel } from '@/modules/mpi/mpiModel'
import { exactNanos, formatNanos } from '@/modules/rmi/labels'
import { gatherAsTransfers, mapOverviewForMap, phaseAsCollective } from './matrixModel'

const PHASES = [
  { key: 'scatter', label: 'Scatter of A' },
  { key: 'broadcast', label: 'Broadcast of B' },
  { key: 'gather', label: 'Gather of C' },
]

const GATHER_TEXT = 'The gather of C is not an Experiment 9 collective: the root fetches each block with its own call, so '
  + 'it has no collective id or outcome of its own. Its fetches are shown here and in the per-node table; the map is '
  + 'drawn for the scatter and the broadcast only.'

/**
 * The three communication phases of the shown run, one at a time: the scatter of A and the
 * broadcast of B through Experiment 9's CollectiveMap and LatencyChart (adapted from
 * MatrixPhaseDto), and the gather of C through LatencyChart only. Every text about how a time was
 * measured is the backend's.
 *
 * @param {object} props
 * @param {object|null} props.overview the MatrixOverviewDto (phaseTimeLabel, collectiveIdsNote)
 * @param {object|null} props.run the MatrixRunDto
 */
export function PhaseViews({ overview, run }) {
  const [phase, setPhase] = useState('scatter')
  if (!run || run.outcome === 'REFUSED') return null
  const collective = phase === 'gather' ? null : phaseAsCollective(run, phase)
  const gathered = gatherAsTransfers(run)
  return (
    <div data-testid="phase-views" className="space-y-3">
      <div role="group" aria-label="Communication phase" className="flex flex-wrap gap-2">
        {PHASES.map((item) => (
          <Button key={item.key} type="button" size="sm" variant={phase === item.key ? 'default' : 'outline'}
            aria-pressed={phase === item.key} onClick={() => setPhase(item.key)}>
            {item.label}
          </Button>
        ))}
      </div>
      {phase !== 'gather' && (collective ? (
        <div className="space-y-3">
          <CollectiveMap overview={mapOverviewForMap(overview)} collective={collective} />
          <p className="flex flex-wrap items-center gap-2 text-sm">
            <OutcomeBadge outcome={collective.outcome} />
            <span>
              Phase time:{' '}
              {formatNanos(collective.totalNanos)
                ? <span className="tabular-nums" title={exactNanos(collective.totalNanos)}>{formatNanos(collective.totalNanos)}</span>
                : <span className="text-muted-foreground">Not measured (no remote transfer was attempted)</span>}
            </span>
          </p>
          {overview?.phaseTimeLabel && <p className="text-xs text-muted-foreground">{overview.phaseTimeLabel}</p>}
          <LatencyChart model={chartModel(collective)} latencyBasis={collective.latencyBasis ?? null} />
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">This run has no {phase} phase.</p>
      ))}
      {phase === 'gather' && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{GATHER_TEXT}</p>
          {gathered
            ? <LatencyChart model={chartModel(gathered)} latencyBasis={run.gatherLatencyBasis ?? null} />
            : <p className="text-sm text-muted-foreground">Nothing was gathered in this run.</p>}
        </div>
      )}
      {overview?.collectiveIdsNote && <p className="text-xs text-muted-foreground">{overview.collectiveIdsNote}</p>}
    </div>
  )
}
