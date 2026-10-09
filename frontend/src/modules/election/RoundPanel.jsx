import { forwardRef } from 'react'
import { DETECTION_NOTE, algorithmLabel, formatMillis, outcomeLabel, secondsFrom, triggerLabel } from './labels'
import { detectorState } from './electionModel'

const NOT_AVAILABLE = <span aria-label="Not available">—</span>

function roundTitle(round) {
  return `${algorithmLabel(round.algorithm)} election, round ${round.roundId}, from node ${round.initiatorNodeId}`
}

function ConsensusLine({ consensus }) {
  if (!consensus) {
    return null
  }
  let text
  if (consensus.passed) {
    text = `Consensus check passed: every live node agrees on node ${consensus.coordinatorId}.`
  } else if (consensus.reached && consensus.coordinatorId != null) {
    text = `Consensus check not passed: every live node still names node ${consensus.coordinatorId}, but that node is down.`
  } else if (consensus.disagreeingNodes?.length > 0) {
    text = `Consensus check not passed: node ${consensus.disagreeingNodes.join(', node ')} ${consensus.disagreeingNodes.length === 1 ? 'reports' : 'report'} another leader or none.`
  } else {
    text = 'Consensus check not passed: no live node knows a leader.'
  }
  return <p data-testid="consensus" className="text-sm">{text}</p>
}

/**
 * The result panel: the detector state (idle until the first election, E4c lazy start), the
 * leader from cluster roles, the open round, the last finished round with its measured
 * duration ("—" when not measured), and the consensus check. A Ring election with one live
 * node gets a clear message, and a timed-out round says so, with the timeout from the
 * backend's settings. The heading is the focus target after an election starts.
 */
export const RoundPanel = forwardRef(function RoundPanel({ overview, leaderId, ringOfOne }, headingRef) {
  const detector = detectorState(overview)
  const settings = overview?.settings
  const timeoutSeconds = secondsFrom(settings?.roundTimeoutMillis)
  const current = overview?.currentRound ?? null
  const last = overview?.lastRound ?? null

  return (
    <section data-testid="round-panel" aria-labelledby="election-result-heading"
      className="relative space-y-3 rounded-lg border bg-card p-4">
      <h3 id="election-result-heading" ref={headingRef} tabIndex={-1}
        className="text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
        Election result
      </h3>

      <p data-testid="detector-state" className="text-sm text-muted-foreground">
        {detector === 'idle' && 'Detector idle, starts on the first election.'}
        {detector === 'running' && settings
          && `Failure detector running: a heartbeat every ${settings.heartbeatIntervalMillis} ms; a node is suspected after ${settings.heartbeatTimeoutMillis} ms of silence.`}
      </p>

      <p className="text-sm">
        Leader: <span className="font-medium">{leaderId != null ? `node ${leaderId}` : 'none'}</span>
        <span className="text-muted-foreground"> (from the cluster roles)</span>
      </p>

      {current && (
        <div data-testid="current-round" className="space-y-2 rounded-md border border-primary/40 p-3 text-sm">
          <p>
            <span className="font-medium">{roundTitle(current)}</span>
            <span className="text-muted-foreground">. {triggerLabel(current.trigger)}. In progress.</span>
          </p>
          {current.algorithm === 'RING' && ringOfOne && (
            <p role="status" className="rounded border border-warning/60 bg-warning/10 p-2">
              Only one node is up, so a Ring election cannot elect anyone: the token has no other node to visit.
              This round will be closed as timed out after {timeoutSeconds ?? '—'} s.
            </p>
          )}
        </div>
      )}

      {last && (
        <div data-testid="last-round" className="space-y-1 text-sm">
          <p className="font-medium">{roundTitle(last)}</p>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt className="text-muted-foreground">Why</dt>
            <dd>{triggerLabel(last.trigger)}</dd>
            <dt className="text-muted-foreground">Outcome</dt>
            <dd>{outcomeLabel(last.outcome)}</dd>
            <dt className="text-muted-foreground">Agreed leader</dt>
            <dd>{last.leaderId != null ? `Node ${last.leaderId}` : NOT_AVAILABLE}</dd>
            <dt className="text-muted-foreground">Duration</dt>
            <dd>
              {formatMillis(last.durationMillis) != null ? `${formatMillis(last.durationMillis)} ms` : NOT_AVAILABLE}
              {last.trigger === 'LEADER_FAILURE' && last.durationMillis != null && (
                <span className="text-muted-foreground"> ({DETECTION_NOTE.toLowerCase()})</span>
              )}
            </dd>
          </dl>
          {last.outcome === 'TIMED_OUT' && (
            <p className="text-muted-foreground">
              Timed out after {timeoutSeconds ?? '—'} s: no leader was agreed, so nothing was measured.
            </p>
          )}
        </div>
      )}

      {!current && !last && detector !== 'unknown' && (
        <p className="text-sm text-muted-foreground">No election has finished yet. Start one with the controls above.</p>
      )}

      <ConsensusLine consensus={overview?.consensus} />
    </section>
  )
})
