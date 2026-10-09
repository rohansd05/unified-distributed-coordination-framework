import { useEffect, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { useModuleEvents } from '@/hooks/useModuleEvents'
import { useStomp } from '@/hooks/useStomp'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { ElectionControls } from './ElectionControls'
import { ElectionRing } from './ElectionRing'
import { electionApi } from './electionApi'
import { describeLeader, describeRoundEnd, describeRoundStart, isRingOfOne, leaderIdFrom, roundMessages } from './electionModel'
import { DETECTION_NOTE, formatMillis, moduleStatusLabel, triggerLabel } from './labels'
import { LeaderChanges } from './LeaderChanges'
import { RoundPanel } from './RoundPanel'
import { useElection } from './useElection'
import { useLeaderChanges } from './useLeaderChanges'

/** Module events read for the message arrows of the current or last round. */
export const ROUND_EVENT_LIMIT = 200

const HOW_IT_WORKS = [
  'The nodes must agree on one leader with no referee. Each node keeps its own view of who the leader is, and every election message travels as a real network datagram carrying the sender\'s Lamport time.',
  'Bully: a node sends an election message to every node with a higher id. Any live higher node answers and takes over; a node that hears no answer in time declares itself coordinator and announces it to everyone. The highest live node always wins.',
  'Ring: the nodes form a ring in id order. A token travels round it, collecting the id of every live node; a node first probes its successor and skips it if no answer comes. When the token returns, the highest id it collected wins, and a second pass tells every node.',
  'Every node also sends a heartbeat to every other node. When a node stops hearing the leader, it suspects it and starts a Bully election by itself, so the cluster elects a new leader with no click.',
]

const WHAT_TO_NOTICE = [
  'Crash the leader and do nothing: once the heartbeat timeout passes, the other nodes suspect it and elect the highest live node by themselves. The duration shown starts at that detection, not at the crash.',
  'Run Ring with a node crashed: the token skips the dead node after its probe goes unanswered, and the log shows the skip.',
  'Recover the highest node: it starts its own Bully election and takes leadership back.',
]

/**
 * Experiment 4, Bully and Ring Election (docs/HANDOFF.md 7): start Bully or Ring from any node,
 * crash and recover nodes, watch the messages on the ring, the leader (from cluster roles),
 * the consensus check and the automatic re-election after the leader fails. Built on the
 * shared kit; live through /topic/modules/election and /topic/cluster, with no polling loop.
 *
 * Honest by construction: every figure comes from the backend; a figure that was not measured
 * shows "—"; nothing here is simulated, so no Simulated badge is needed; the detector is shown
 * idle until the first election starts it.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createElectionApi; for tests
 */
export function ElectionPage({ experiment, api = electionApi }) {
  const { overview, loading, error, refresh } = useElection({ api })
  const { events } = useModuleEvents({ moduleId: experiment.id, limit: ROUND_EVENT_LIMIT, api })
  const leaderChanges = useLeaderChanges({ api })
  const { cluster } = useCluster()
  const { status: connection } = useStomp()
  const [message, setMessage] = useState('')
  const announcement = usePoliteAnnouncement(message)
  const resultHeadingRef = useRef(null)
  const statusLineRef = useRef(null)

  const leaderId = leaderIdFrom(cluster)
  const current = overview?.currentRound ?? null
  const last = overview?.lastRound ?? null
  const shownRound = current ?? last
  const messages = roundMessages(events, shownRound)
  const unreachable = connection === 'reconnecting' || connection === 'error' || Boolean(error)
  const hasData = Boolean(overview)

  // Announce round start, round end and leader change; nothing for what was already there on load.
  const seenRef = useRef(null)
  useEffect(() => {
    if (!overview) {
      return
    }
    const seen = { current: current?.roundId ?? null, last: last?.roundId ?? null, leader: leaderId }
    const before = seenRef.current
    seenRef.current = seen
    if (!before) {
      return
    }
    const parts = []
    if (current && current.roundId !== before.current) {
      parts.push(describeRoundStart(current))
    }
    if (last && last.roundId !== before.last) {
      parts.push(describeRoundEnd(last, overview.settings?.roundTimeoutMillis))
    }
    if (leaderId !== before.leader) {
      parts.push(describeLeader(leaderId))
    }
    if (parts.length > 0) {
      setMessage(parts.join(' '))
    }
  }, [overview, current, last, leaderId])

  function handleStarted(round) {
    setMessage(describeRoundStart(round))
    const target = resultHeadingRef.current ?? statusLineRef.current
    target?.focus()
  }

  const controls = (
    <div className="relative space-y-4">
      <p aria-live="polite" className="sr-only">{announcement}</p>
      {unreachable && (
        <div role="alert" className="rounded-lg border border-warning/60 bg-warning/10 p-4 text-sm">
          <p className="font-semibold">The backend is not reachable.</p>
          <p className="mt-1 text-muted-foreground">
            Start it again with <code className="font-mono text-foreground">cd backend; .\mvnw.cmd spring-boot:run</code>.
            This page reconnects and refreshes by itself.
          </p>
          {error?.detail && <p className="mt-1 text-xs text-muted-foreground">Last error: {error.detail}</p>}
        </div>
      )}
      {!hasData && loading && (
        <div className="h-40 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" aria-busy="true">
          <span className="sr-only">Loading the election module…</span>
        </div>
      )}
      {!hasData && !loading && error && (
        <div className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the election module.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <p ref={statusLineRef} tabIndex={-1} className="text-sm focus-visible:outline-none" data-testid="module-status">
            Module status: <span className="font-medium">{moduleStatusLabel(overview.status)}</span>
          </p>
          <ElectionControls overview={overview} api={api} onStarted={handleStarted} onChanged={refresh}
            onAnnounce={setMessage} />
        </>
      )}
    </div>
  )

  const visualisation = (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
      <ElectionRing nodes={overview?.nodes ?? null} leaderId={leaderId} messages={messages} />
      <div className="space-y-6">
        <RoundPanel ref={resultHeadingRef} overview={overview} leaderId={leaderId} ringOfOne={isRingOfOne(overview)} />
        <LeaderChanges {...leaderChanges} />
      </div>
    </div>
  )

  const nodes = Array.isArray(overview?.nodes) ? overview.nodes : []
  const measurements = (
    <div className="space-y-4">
      <div className="grid grid-cols-1 divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <MetricCard label="Leader" value={leaderId != null ? `Node ${leaderId}` : null} hint="From the cluster roles" />
        <MetricCard label="Last election took" value={formatMillis(last?.durationMillis)} unit="ms"
          hint={last ? `${triggerLabel(last.trigger)}${last.trigger === 'LEADER_FAILURE' ? `; ${DETECTION_NOTE.toLowerCase()}` : ''}` : 'No election has finished yet'} />
        <MetricCard label="Consensus check" value={overview?.consensus ? (overview.consensus.passed ? 'Passed' : 'Not passed') : null}
          hint="Every live node names the same live leader" />
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Per node, since the backend started</h3>
        <ul className="divide-y rounded-lg border bg-card" data-testid="per-node-metrics">
          {nodes.map((node) => (
            <li key={node.nodeId} className="grid grid-cols-2">
              <MetricCard label={`Node ${node.nodeId}: elections won`} value={node.electionsWon} />
              <MetricCard label={`Node ${node.nodeId}: mean election time`} value={formatMillis(node.meanDurationMillis)} unit="ms"
                hint={`${node.roundsTimed} measured ${node.roundsTimed === 1 ? 'election' : 'elections'} started here`} />
            </li>
          ))}
        </ul>
        <p className="text-xs text-muted-foreground">
          Elections won counts the elections each node won. Mean election time covers elections started from that node
          that ended with an agreed leader; an election that timed out is not measured. Every figure is measured; nothing
          on this page is simulated.
        </p>
      </div>
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={WHAT_TO_NOTICE}
    />
  )
}
