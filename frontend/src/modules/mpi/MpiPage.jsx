import { useEffect, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { NotAvailable } from '@/modules/rmi/CallResult'
import { CollectiveMap } from './CollectiveMap'
import { CollectiveResult, TOTAL_TIME_BASIS } from './CollectiveResult'
import { HistoryTable } from './HistoryTable'
import { exactNanos, formatNanos, moduleStatusLabel, operationLabel, outcomeLabel } from './labels'
import { LatencyChart } from './LatencyChart'
import { MpiControls } from './MpiControls'
import { describeProblem, mpiApi } from './mpiApi'
import { chartModel, historyRows, noticeModel, transferCounts } from './mpiModel'
import { NodeStrip } from './NodeStrip'
import { useMpi } from './useMpi'

const HOW_IT_WORKS = [
  'Every node exports a second remote object, CollectiveEndpoint, in the same RMI registry that Experiment 1 uses. One node is the root of a collective; every live node is a rank, numbered in node order, and a crashed node is skipped.',
  'Broadcast sends the root\'s whole vector to every rank. Scatter splits it into one block per rank and sends each rank its own block. Gather asks every rank to compute its own block and send it back, and the root puts the blocks together in rank order.',
  'Each transfer is a real RMI call over a loopback socket, timed on the root and checked by SHA-256 checksum; the vectors themselves never reach this page. Every call carries the root\'s Lamport time.',
]

/** A figure for MetricCard: the formatted text, or "n/a" with its reason (MetricCard itself is unchanged). */
function metric(text, exact, reason) {
  if (text === null || text === undefined) return <NotAvailable reason={reason} />
  return <span title={exact ?? undefined}>{text}</span>
}

/** Copied from the Experiment 1 page (RmiPage); a shared component is a later refactor. */
function ProblemAlert({ problem, alertRef }) {
  if (!problem) return null
  return (
    <div ref={alertRef} tabIndex={-1} role="alert" data-testid="action-error" data-kind={problem.kind}
      className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm focus:outline-none">
      <p className="font-medium text-destructive">{problem.message}</p>
      {problem.kind === 'busy' && problem.actionInProgress && (
        <p className="mt-1 text-muted-foreground">Running now: {problem.actionInProgress}</p>
      )}
      {problem.fieldErrors && (
        <ul className="mt-1 list-disc pl-5 text-muted-foreground">
          {Object.entries(problem.fieldErrors).map(([field, text]) => <li key={field}>{field}: {text}</li>)}
        </ul>
      )}
    </div>
  )
}

function verificationText(collective) {
  const passed = collective?.verification?.passed
  if (passed === true) return 'Passed'
  if (passed === false) return 'Failed'
  return null
}

/**
 * Experiment 9, MPI Collectives (docs/HANDOFF.md 7): choose broadcast, scatter or gather, a vector
 * length, a seed and a root, run it over every live node through each node's RMI registry, and
 * see every transfer as measured, its checksum check, the total time and how a crashed node is
 * reported. Built on the shared kit; live through /topic/modules/mpi and /topic/cluster.
 *
 * Honest by construction: every figure is the backend's; a null is "n/a" with its reason, never
 * 0; a latency is shown only for a delivered transfer and a time to failure only for a failed
 * one; the total time is one measurement, never a round trip; the only Simulated badge is the
 * co-hosting of the nodes in one process.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createMpiApi; for tests
 */
export function MpiPage({ experiment, api = mpiApi }) {
  const data = useMpi({ api })
  const { overview, history, loading, error, historyError, starting, startError, refresh } = data
  const { refresh: refreshCluster } = useCluster()
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [action, setAction] = useState({ pending: null, problem: null, result: null })
  const [selectedKey, setSelectedKey] = useState(null)
  const [message, setMessage] = useState('')
  const announcement = usePoliteAnnouncement(message)
  const resultRef = useRef(null)
  const errorRef = useRef(null)
  const [focusTarget, setFocusTarget] = useState(null)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  useEffect(() => {
    if (focusTarget === 'result') resultRef.current?.focus()
    if (focusTarget === 'error') errorRef.current?.focus()
    if (focusTarget) setFocusTarget(null)
  }, [focusTarget])

  async function perform(pendingText, run, onSuccess) {
    setAction({ pending: pendingText, problem: null, result: null })
    try {
      const result = await run()
      if (!mountedRef.current) return
      setAction({ pending: null, problem: null, result })
      setFocusTarget(result ? 'result' : null)
      onSuccess(result)
    } catch (err) {
      if (!mountedRef.current) return
      setAction({ pending: null, problem: describeProblem(err), result: null })
      setFocusTarget('error')
    }
    refresh()
  }

  function runCollective(body) {
    perform(`Running ${operationLabel(body.operation).toLowerCase()} of ${body.count} values...`,
      () => api.runCollective(body, overview),
      (collective) => {
        const name = `${operationLabel(collective.operation)} ${collective.id}`
        if (collective.outcome === 'REFUSED') {
          toast({ title: `${name} refused`, description: collective.refusal?.message })
          setMessage(`${name} was refused: ${collective.refusal?.message ?? ''}`)
          return
        }
        const counts = transferCounts(collective)
        const total = formatNanos(collective.totalNanos)
        const text = `${name}: ${outcomeLabel(collective.outcome).toLowerCase()}, ${counts.delivered} of ${counts.remote} remote `
          + `transfers delivered${total ? `, total ${total}` : ''}.`
        toast(collective.outcome === 'COMPLETE' ? { title: text } : { variant: 'destructive', title: text })
        setMessage(text)
      })
  }

  function nodeAction(nodeId, crash) {
    perform(`${crash ? 'Crashing' : 'Recovering'} node ${nodeId}...`,
      async () => {
        await (crash ? api.crashNode(nodeId) : api.recoverNode(nodeId))
        return null
      },
      () => {
        const text = `Node ${nodeId} ${crash ? 'crashed' : 'recovered'} for every experiment.`
        toast({ title: text })
        setMessage(text)
        refreshCluster()
      })
  }

  const hasData = Boolean(overview)
  const shown = overview?.latest ?? action.result ?? null
  const counts = transferCounts(shown)
  const rows = historyRows(history)
  const selected = selectedKey ? rows.find((row) => row.key === selectedKey) ?? null : null
  const participants = overview?.participants ?? []
  const exported = participants.filter((node) => node.collectiveEndpointExported).length
  const unreachable = connection === 'reconnecting' || connection === 'error' || (hasData && Boolean(error))

  const controls = (
    <div className="space-y-4">
      <p aria-live="polite" className="sr-only">{announcement}</p>
      {unreachable && (
        <div role="alert" className="rounded-lg border border-warning/60 bg-warning/10 p-4 text-sm">
          <p className="font-semibold">The backend is not reachable.</p>
          <p className="mt-1 text-muted-foreground">
            Start it again with <code className="font-mono text-foreground">cd backend; .\mvnw.cmd spring-boot:run</code>.
            This page reconnects and refreshes by itself.
          </p>
        </div>
      )}
      {!hasData && loading && (
        <div data-testid="loading" className="h-40 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none" aria-busy="true">
          <span className="sr-only">Loading the MPI module...</span>
        </div>
      )}
      {!hasData && !loading && (
        <div role="alert" data-testid="load-error" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the MPI module.</p>
          <p className="mt-1 text-muted-foreground">{describeProblem(error ?? startError)?.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <p className="text-sm" data-testid="module-status">
            Module status: <span className="font-medium">{moduleStatusLabel(overview.status)}</span>
            <span className="text-muted-foreground">. CollectiveEndpoint exported on {exported} of {participants.length} nodes.</span>
          </p>
          {starting && (
            <p role="status" className="text-sm text-muted-foreground">Starting RMI on every live node...</p>
          )}
          {startError && !starting && (
            <div role="alert" data-testid="start-error" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
              <p className="font-medium text-destructive">RMI could not be started: {describeProblem(startError).message}</p>
              <Button variant="outline" size="sm" className="mt-2" onClick={data.start}>Try again</Button>
            </div>
          )}
          {overview.status === 'BUSY' && !action.pending && (
            <p role="status" data-testid="busy-banner" className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
              The backend is busy with another collective{overview.actionInProgress ? `: ${overview.actionInProgress}` : ''}.
              The button stays available; the backend answers that it is busy until it has finished.
            </p>
          )}
          <MpiControls overview={overview} pending={action.pending} serverErrors={action.problem?.fieldErrors}
            onRun={runCollective} onCrash={(id) => nodeAction(id, true)} onRecover={(id) => nodeAction(id, false)} />
          <ProblemAlert problem={action.problem} alertRef={errorRef} />
          {action.result && (
            <div ref={resultRef} tabIndex={-1} role="region" aria-label="Result" data-testid="action-result"
              className="rounded-md border bg-background/60 p-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <CollectiveResult collective={action.result} />
            </div>
          )}
        </>
      )}
    </div>
  )

  const visualisation = (
    <div className="space-y-5">
      <CollectiveMap overview={overview} collective={shown} />
      <NodeStrip participants={overview?.participants} />
    </div>
  )

  const noCollective = 'No collective yet'
  const measurements = (
    <div className="space-y-4">
      <div className="grid grid-cols-1 divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <MetricCard label="Last total time"
          value={metric(formatNanos(shown?.totalNanos), exactNanos(shown?.totalNanos),
            !shown ? noCollective : shown.outcome === 'REFUSED' ? 'Refused: nothing was sent' : 'No remote transfer was attempted')}
          hint={shown ? 'One measurement on the root, not a round trip' : 'Start with a broadcast'} />
        <MetricCard label="Remote transfers delivered"
          value={metric(shown && counts.remote > 0 ? `${counts.delivered} of ${counts.remote}` : null, null,
            shown ? 'No remote transfer in this collective' : noCollective)}
          hint={shown && (counts.failed > 0 || counts.notAttempted > 0)
            ? `${counts.failed} failed, ${counts.notAttempted} not attempted` : undefined} />
        <MetricCard label="Checksum verification"
          value={metric(verificationText(shown), null, shown ? 'Unknown, or nothing was checked' : noCollective)} />
      </div>
      <p className="text-xs text-muted-foreground"><span className="font-medium text-foreground">Total time.</span> {TOTAL_TIME_BASIS}</p>
      <LatencyChart model={chartModel(shown)} latencyBasis={shown?.latencyBasis ?? null} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Recent collectives</h3>
        {historyError && !Array.isArray(history) ? (
          <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
            <p className="font-semibold text-destructive">Could not load the collective history.</p>
            <p className="mt-1 text-muted-foreground">{describeProblem(historyError).message}</p>
            <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
          </div>
        ) : (
          <HistoryTable rows={rows} historySize={overview?.limits?.historySize ?? null} selectedKey={selectedKey}
            onSelect={(row) => setSelectedKey(row.key)} />
        )}
        {selectedKey && (
          <div data-testid="history-detail" className="rounded-md border bg-background/60 p-3 text-sm">
            {selected
              ? <CollectiveResult collective={selected.entry} />
              : <p role="alert" className="text-muted-foreground">That collective is no longer in the history: the backend keeps only the latest, and ids restart after a cluster reset.</p>}
          </div>
        )}
      </div>
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={[...HOW_IT_WORKS, ...(overview?.algorithmNote ? [overview.algorithmNote] : [])]
        .map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={noticeModel(history)}
    />
  )
}
