import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { EXPERIMENTS } from '@/lib/experiments'
import { outcomeLabel } from '@/modules/mpi/labels'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { exactNanos, formatNanos, moduleStatusLabel } from '@/modules/rmi/labels'
import { HistoryTable } from './HistoryTable'
import { MatrixControls } from './MatrixControls'
import { describeProblem, matrixApi } from './matrixApi'
import { allNodesDown, formatShare, historyRows, noticeModel } from './matrixModel'
import { PhaseViews } from './PhaseViews'
import { RowBlocks } from './RowBlocks'
import { Missing, RunResult } from './RunResult'
import { SpeedupChart } from './SpeedupChart'
import { BUSY_POLL_INTERVAL_MS, useMatrix } from './useMatrix'

const HOW_IT_WORKS = [
  'The root generates two square matrices, A and B, from a seed, and first multiplies them on its own: that sequential product is both the reference result and the time to beat.',
  'For the parallel run the root splits A into blocks of whole rows, one block per live node, scatters them and broadcasts B with Experiment 9\'s collectives; every node multiplies its rows by B on its own Experiment 2 executor; then the root fetches each block of C and puts C together.',
  'The assembled C is compared with the sequential product bit for bit. Only a complete run that matches gets a speedup: the sequential time divided by the parallel time, both measured on the root.',
]

const EXPERIMENT_TWO = EXPERIMENTS.find((experiment) => experiment.id === 'multithreading')
const linkClass = 'font-medium text-primary underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

/** Copied from the Experiment 9 page (MpiPage); a shared component is a later refactor. */
function ProblemAlert({ problem, alertRef }) {
  if (!problem) return null
  return (
    <div ref={alertRef} tabIndex={-1} role="alert" data-testid="action-error" data-kind={problem.kind}
      className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm focus:outline-none">
      <p className="font-medium">{problem.message}</p>
      {problem.kind === 'busy' && problem.actionInProgress && (
        <p className="mt-1 text-muted-foreground">Running now: {problem.actionInProgress}</p>
      )}
      {problem.kind === 'state-conflict' && problem.nodeId === 0 && (
        <p className="mt-1 text-muted-foreground">
          No node is live, so nothing can be the root. Recover a node on the <Link to="/cluster" className={linkClass}>Cluster page</Link>.
        </p>
      )}
      {problem.kind === 'timeout' && (
        <p className="mt-1 text-muted-foreground">The run may still be going on the backend; this page checks until the module is idle again.</p>
      )}
      {problem.fieldErrors && (
        <ul className="mt-1 list-disc pl-5 text-muted-foreground">
          {Object.entries(problem.fieldErrors).map(([field, text]) => <li key={field}>{field}: {text}</li>)}
        </ul>
      )}
    </div>
  )
}

function runSummary(run) {
  const name = `Run ${run.runId}, size ${run.size}`
  if (run.outcome === 'REFUSED') return `${name} was refused: ${run.refusal?.message ?? ''}`
  return `${name}: ${outcomeLabel(run.outcome).toLowerCase()}.`
}

function verificationText(run) {
  const passed = run?.verification?.passed
  if (passed === true) return 'Passed'
  if (passed === false) return 'Failed'
  return null
}

const nanosValue = (nanos, reason) => (formatNanos(nanos)
  ? <span title={exactNanos(nanos)}>{formatNanos(nanos)}</span>
  : <Missing reason={reason} />)

/**
 * Experiment 10, Parallel Matrix Multiplication (docs/HANDOFF.md 7): choose a size, run it over
 * every live node (scatter A by rows, broadcast B, multiply on each node's Experiment 2 executor,
 * gather C) and see the sequential and parallel times, the speedup, each rank's rows and compute
 * time, communication against computation and the checksum verification; run every configured
 * size for the speedup chart. Built on the shared kit; live through /topic/modules/matrix and
 * /topic/cluster.
 *
 * Honest by construction: every figure is the backend's and every limit comes from the overview;
 * a null is "Not measured" or "Not available", never 0; the speedup is shown only as served
 * (withheld with its reason otherwise) and always beside its basis; the only Simulated badge is
 * the co-hosting of the nodes in one process.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createMatrixApi; for tests
 */
export function MatrixPage({ experiment, api = matrixApi }) {
  const data = useMatrix({ api })
  const { overview, runs, series, loading, error, runsError, seriesError, starting, startError, refresh, problem, lastRun } = data
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [selectedKey, setSelectedKey] = useState(null)
  const [message, setMessage] = useState('')
  const announcement = usePoliteAnnouncement(message)
  const errorRef = useRef(null)

  useEffect(() => {
    if (!lastRun) return
    const text = runSummary(lastRun)
    toast(lastRun.outcome === 'COMPLETE' || lastRun.outcome === 'REFUSED' ? { title: text } : { variant: 'destructive', title: text })
    setMessage(text)
  }, [lastRun, toast])

  useEffect(() => {
    if (problem) errorRef.current?.focus()
  }, [problem])

  useEffect(() => {
    if (data.runAllNote) setMessage(data.runAllNote)
  }, [data.runAllNote])

  const hasData = Boolean(overview)
  const shown = overview?.latest ?? lastRun ?? null
  const rows = historyRows(runs)
  const selected = selectedKey ? rows.find((row) => row.key === selectedKey) ?? null : null
  const participants = overview?.participants ?? []
  const exported = participants.filter((node) => node.matrixEndpointExported).length
  const listening = participants.filter((node) => node.requestListenerRunning).length
  const failedStarts = participants.filter((node) => node.nodeStatus === 'UP' && node.startFailure)
  const sizes = overview?.limits?.seriesSizes ?? []
  const unreachable = connection === 'reconnecting' || connection === 'error' || (hasData && Boolean(error))
  const busyElsewhere = overview?.status === 'BUSY' && !data.pending && !data.progress && !data.awaitingIdle
  const pollSeconds = BUSY_POLL_INTERVAL_MS / 1000

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
          <span className="sr-only">Loading the matrix module...</span>
        </div>
      )}
      {!hasData && !loading && (
        <div role="alert" data-testid="load-error" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the matrix module.</p>
          <p className="mt-1 text-muted-foreground">{describeProblem(error ?? startError)?.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <div className="space-y-2 rounded-lg border bg-card/60 p-4 text-sm">
            <p data-testid="module-status">
              Module status: <span className="font-medium">{moduleStatusLabel(overview.status)}</span>
              <span className="text-muted-foreground">. MatrixEndpoint exported on {exported} of {participants.length} nodes;
                request listeners running on {listening} of {participants.length}.</span>
            </p>
            {starting && <p role="status" className="text-muted-foreground">Starting RMI and the request listeners on every live node...</p>}
            {startError && !starting && (
              <div role="alert" data-testid="start-error" className="rounded-md border border-destructive/40 bg-destructive/10 p-3">
                <p className="font-medium text-destructive">The module could not be started: {describeProblem(startError).message}</p>
                <Button variant="outline" size="sm" className="mt-2" onClick={data.start}>Try again</Button>
              </div>
            )}
            {!startError && !starting && failedStarts.length > 0 && (
              <div role="alert" data-testid="start-failure" className="rounded-md border border-destructive/40 bg-destructive/10 p-3">
                {failedStarts.map((node) => (
                  <p key={node.nodeId} className="break-words">Node {node.nodeId} could not start: {node.startFailure}</p>
                ))}
                <Button variant="outline" size="sm" className="mt-2" onClick={data.start}>Try again</Button>
              </div>
            )}
            <p data-testid="experiment-two" className="text-muted-foreground">
              {overview.experimentTwoSideEffect}{' '}
              {EXPERIMENT_TWO && <Link to={`/experiments/${EXPERIMENT_TWO.slug}`} className={linkClass}>Open the Experiment 2 page</Link>}
            </p>
          </div>
          {busyElsewhere && (
            <p role="status" data-testid="busy-banner" className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
              The module is busy{overview.actionInProgress ? `: ${overview.actionInProgress}` : ''}. A run started elsewhere (or
              before a reload) still holds it; this page checks every {pollSeconds} s and shows the run when it ends. Run stays
              available; the backend answers that it is busy until then.
            </p>
          )}
          {data.awaitingIdle && (
            <p role="status" data-testid="awaiting-idle" className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
              No answer came in time, so the run may still be going. The controls stay locked while the module is busy; this page
              checks every {pollSeconds} s.
            </p>
          )}
          {allNodesDown(overview) && (
            <p role="status" data-testid="all-down" className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
              Every node is down, so no run can have a root. Recover a node on the <Link to="/cluster" className={linkClass}>Cluster page</Link>.
            </p>
          )}
          {Array.isArray(runs) && runs.length === 0 && !shown && sizes.length > 0 && (
            <p data-testid="empty-state" className="text-sm text-muted-foreground">
              No run yet. Start with size {sizes[0]}, or run all sizes ({sizes.join(', ')}) to fill the speedup chart.
            </p>
          )}
          <MatrixControls overview={overview} locked={data.locked} pending={data.pending} progress={data.progress}
            stopRequested={data.stopRequested} serverErrors={problem?.fieldErrors}
            onRun={(size, rootNode) => data.run(size, rootNode)} onRunAll={(rootNode) => data.runAll(rootNode)}
            onStop={data.stopAfterCurrent} />
          <ProblemAlert problem={problem} alertRef={errorRef} />
          {data.runAllNote && <p role="status" data-testid="run-all-note" className="text-sm">{data.runAllNote}</p>}
          <p className="text-xs text-muted-foreground">
            To crash or recover a node, use the <Link to="/cluster" className={linkClass}>Cluster page</Link>: it affects every experiment on that node.
          </p>
        </>
      )}
    </div>
  )

  const visualisation = (
    <div className="space-y-6">
      <RowBlocks overview={overview} run={shown} />
      <PhaseViews key={shown ? `${shown.runId}-${shown.startedAt}` : 'none'} overview={overview} run={shown} />
    </div>
  )

  const noRun = 'No run yet'
  const derived = shown?.timing?.derived
  const measurements = (
    <div className="space-y-5">
      <div className="grid grid-cols-1 divide-y rounded-lg border bg-card sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-4 lg:divide-x">
        <MetricCard label="Sequential time" value={nanosValue(shown?.timing?.measured?.sequentialNanos, shown ? 'Not measured in this run' : noRun)}
          hint="Measured on the root" />
        <MetricCard label="Parallel time" value={nanosValue(shown?.timing?.measured?.parallelTotalNanos, shown ? 'Not measured in this run' : noRun)}
          hint="Measured on the root" />
        <MetricCard label="Communication share"
          value={formatShare(derived?.communicationShare)
            ? <span title={String(derived.communicationShare)}>{formatShare(derived.communicationShare)}</span>
            : <Missing reason={shown ? 'Not derived: a phase time is unknown, or the run was refused' : noRun} />}
          hint="Derived: scatter, broadcast and gather over the parallel time" />
        <MetricCard label="Verification"
          value={verificationText(shown) ?? <Missing text="Not available" reason={shown ? 'Unknown, or nothing was assembled' : noRun} />}
          hint="Against the sequential product" />
      </div>
      {shown
        ? <RunResult run={shown} overview={overview} />
        : <p className="rounded-lg border bg-card p-4 text-sm text-muted-foreground">The figures of the latest run appear here.</p>}
      {seriesError && !series ? (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
          <p className="font-semibold text-destructive">Could not load the speedup series.</p>
          <p className="mt-1 text-muted-foreground">{describeProblem(seriesError).message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      ) : <SpeedupChart series={series} overview={overview} />}
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Recent runs</h3>
        {runsError && !Array.isArray(runs) ? (
          <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
            <p className="font-semibold text-destructive">Could not load the run history.</p>
            <p className="mt-1 text-muted-foreground">{describeProblem(runsError).message}</p>
            <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
          </div>
        ) : (
          <HistoryTable rows={rows} historySize={overview?.limits?.historySize ?? null} selectedKey={selectedKey}
            onSelect={(row) => setSelectedKey(row.key)} />
        )}
        {selectedKey && (
          <div data-testid="history-detail" className="rounded-md border bg-background/60 p-3">
            {selected
              ? <RunResult run={selected.run} overview={overview} />
              : <p role="alert" className="text-sm text-muted-foreground">That run is no longer in the history: the backend keeps only the latest, and ids restart after a cluster reset.</p>}
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
      whatToNotice={noticeModel(runs)}
    />
  )
}
