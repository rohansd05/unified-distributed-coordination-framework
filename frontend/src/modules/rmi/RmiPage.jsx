import { useEffect, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { ProblemAlert } from '@/components/ProblemAlert'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { CallResult, ComparisonResult, NotAvailable } from './CallResult'
import { HistoryTable } from './HistoryTable'
import { exactNanos, failureKindLabel, formatBytes, formatNanos, methodLabel, moduleStatusLabel, stageLabel } from './labels'
import { NodeMap } from './NodeMap'
import { RegistryList } from './RegistryList'
import { RmiControls } from './RmiControls'
import { describeProblem, rmiApi } from './rmiApi'
import { chartModel, historyRows, latestCall, noticeModel } from './rmiModel'
import { RoundTripChart } from './RoundTripChart'
import { useRmi } from './useRmi'

const HOW_IT_WORKS = [
  'Every node runs its own RMI registry on its own port and exports one remote object there, under the name NodeEndpoint. The object has four methods: ping, nodeInfo, echo and computeHash.',
  'To call a node, the calling node first looks the name up in the target node\'s registry and gets back a stub: a local object that stands in for the remote one. Calling a method on the stub serialises the arguments, sends them over a loopback socket, runs the method on the target node and sends the result back.',
  'The page shows each step as measured: the registry lookup, the round trip of the call, and the size of the serialised arguments and reply. Every call carries the caller\'s Lamport time, so the target\'s clock moves past it, and the reply moves the caller\'s clock in turn.',
  'Crash a node and call it: nothing accepts the connection, and RMI raises a RemoteException at once instead of waiting. The local versus remote comparison runs the same method once directly on the target\'s object and once through RMI.',
]

/** A figure for MetricCard: the formatted text, or "n/a" with its reason (MetricCard itself is unchanged). */
function metric(text, exact, reason) {
  if (text === null || text === undefined) return <NotAvailable reason={reason} />
  return <span title={exact ?? undefined}>{text}</span>
}

/**
 * Experiment 1, Client-Server via Java RMI (docs/HANDOFF.md 7): choose a caller, a target and a
 * method, invoke it through the target's RMI registry and stub, and see the result, round trip,
 * lookup time, marshalled sizes and stub class; compare a direct call with an RMI call; call a
 * crashed node and see the real RemoteException; list every node's registry. Built on the
 * shared kit; live through /topic/modules/rmi and /topic/cluster.
 *
 * Honest by construction: every figure is the backend's, shown at the precision the data has;
 * a null is "n/a" with its reason, never 0; a negative overhead stays negative, beside the
 * backend's note; the only Simulated badge is the co-hosting of the nodes in one process.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createRmiApi; for tests
 */
export function RmiPage({ experiment, api = rmiApi }) {
  const data = useRmi({ api })
  const { overview, history, registries, loading, error, historyError, registriesError, starting, startError, refresh } = data
  const { refresh: refreshCluster } = useCluster()
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [action, setAction] = useState({ pending: null, problem: null, result: null })
  const [detail, setDetail] = useState(null)
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

  function invoke(body) {
    perform(`Calling node ${body.targetNode} (${methodLabel(body.method)}) from node ${body.fromNode}...`,
      async () => ({ type: 'call', data: await api.call(body) }),
      ({ data: call }) => {
        if (call.outcome === 'SUCCESS') {
          const text = `${methodLabel(call.method)} to node ${call.targetNode} answered, round trip ${formatNanos(call.roundTripNanos)}.`
          toast({ title: text })
          setMessage(text)
        } else {
          const text = `${failureKindLabel(call.failureKind)} at the ${stageLabel(call.failureStage)}.`
          toast({ variant: 'destructive', title: `${methodLabel(call.method)} to node ${call.targetNode} failed`, description: text })
          setMessage(`The call to node ${call.targetNode} failed: ${text}`)
        }
      })
  }

  function compare(body) {
    perform(`Comparing ${methodLabel(body.method)} on node ${body.targetNode}, direct and through RMI...`,
      async () => ({ type: 'comparison', data: await api.compare(body) }),
      ({ data: comparison }) => {
        const overhead = formatNanos(comparison.overheadNanos)
        const text = `Comparison finished: overhead ${overhead ?? 'n/a'}.`
        toast({ title: text })
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

  async function selectHistory(row) {
    setDetail({ key: row.key, entry: null, problem: null, loading: true })
    try {
      const entry = await api.getCall(row.id)
      if (!mountedRef.current) return
      const calledAt = entry.kind === 'COMPARISON' ? entry.comparison?.calledAt : entry.call?.calledAt
      const stale = entry.kind !== row.kind || calledAt !== row.calledAt
      setDetail({ key: row.key, entry: stale ? null : entry, loading: false,
        problem: stale ? { kind: 'gone', message: `Entry ${row.id} is no longer the one listed: ids restart after a cluster reset. Refreshing the list.` } : null })
      if (stale) refresh()
    } catch (err) {
      if (!mountedRef.current) return
      setDetail({ key: row.key, entry: null, loading: false, problem: describeProblem(err) })
    }
  }

  const hasData = Boolean(overview)
  const call = latestCall(overview?.latest) ?? (action.result?.type === 'call' ? action.result.data : null)
  const rows = historyRows(history)
  const latestComparison = (Array.isArray(history) ? history : []).find((entry) => entry.kind === 'COMPARISON')?.comparison ?? null
  const exported = (overview?.nodes ?? []).filter((node) => node.state === 'EXPORTED').length
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
          <span className="sr-only">Loading the RMI module...</span>
        </div>
      )}
      {!hasData && !loading && (
        <div role="alert" data-testid="load-error" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the RMI module.</p>
          <p className="mt-1 text-muted-foreground">{describeProblem(error ?? startError)?.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          <p className="text-sm" data-testid="module-status">
            Module status: <span className="font-medium">{moduleStatusLabel(overview.status)}</span>
            <span className="text-muted-foreground">. RMI endpoints exported on {exported} of {overview.nodes.length} nodes.</span>
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
              The backend is busy with another action{overview.actionInProgress ? `: ${overview.actionInProgress}` : ''}.
              The buttons stay available; the backend answers that it is busy until it has finished.
            </p>
          )}
          <RmiControls overview={overview} pending={action.pending} serverErrors={action.problem?.fieldErrors}
            onInvoke={invoke} onCompare={compare} onRefreshRegistries={refresh}
            onCrash={(id) => nodeAction(id, true)} onRecover={(id) => nodeAction(id, false)} />
          <ProblemAlert problem={action.problem} alertRef={errorRef} />
          {action.result && (
            <div ref={resultRef} tabIndex={-1} role="region" aria-label="Result" data-testid="action-result"
              className="rounded-md border bg-background/60 p-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              {action.result.type === 'call'
                ? <CallResult call={action.result.data} overview={overview} />
                : <ComparisonResult comparison={action.result.data} overview={overview} />}
            </div>
          )}
        </>
      )}
    </div>
  )

  const visualisation = (
    <div className="space-y-5">
      <NodeMap overview={overview} call={call} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Registries</h3>
        <RegistryList registries={registries} error={registriesError} overview={overview} onRetry={refresh} />
      </div>
    </div>
  )

  const lastCallReason = call ? 'Not measured for this call' : 'No call yet'
  const measurements = (
    <div className="space-y-4">
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-3 sm:divide-y-0 lg:grid-cols-5">
        <MetricCard label="Last round trip"
          value={metric(formatNanos(call?.roundTripNanos), exactNanos(call?.roundTripNanos),
            call ? 'No round trip: the call did not complete' : 'No call yet')}
          hint={call ? `${methodLabel(call.method)} to node ${call.targetNode}` : 'Start with a ping'} />
        <MetricCard label="Last registry lookup"
          value={metric(formatNanos(call?.lookupNanos), exactNanos(call?.lookupNanos),
            call ? 'The registry lookup failed' : 'No call yet')} />
        <MetricCard label="Arguments marshalled" value={metric(formatBytes(call?.argumentBytes), null, lastCallReason)} />
        <MetricCard label="Reply marshalled" value={metric(formatBytes(call?.resultBytes), null, lastCallReason)} />
        <MetricCard label="Last comparison overhead"
          value={metric(formatNanos(latestComparison?.overheadNanos), exactNanos(latestComparison?.overheadNanos),
            latestComparison ? 'Needs both sides to succeed' : 'No comparison yet')}
          hint={latestComparison ? 'Remote minus direct, as measured' : 'Compare local and remote'} />
      </div>
      {overview && (
        <div className="space-y-1 text-xs text-muted-foreground">
          <p><span className="font-medium text-foreground">Lookup time.</span> {overview.lookupBasis}</p>
          <p><span className="font-medium text-foreground">Payload size.</span> {overview.payloadSizeBasis}</p>
        </div>
      )}
      <RoundTripChart model={chartModel(history)} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Recent calls</h3>
        {historyError && !Array.isArray(history) ? (
          <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-4 text-sm">
            <p className="font-semibold text-destructive">Could not load the call history.</p>
            <p className="mt-1 text-muted-foreground">{describeProblem(historyError).message}</p>
            <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
          </div>
        ) : (
          <HistoryTable rows={rows} historySize={overview?.limits?.historySize ?? null} selectedKey={detail?.key ?? null}
            onSelect={selectHistory} />
        )}
        {detail && (
          <div data-testid="history-detail" className="rounded-md border bg-background/60 p-3 text-sm">
            {detail.loading && <p className="text-muted-foreground">Loading the entry...</p>}
            {detail.problem && <p role="alert" className="text-destructive">{detail.problem.message}</p>}
            {detail.entry?.kind === 'CALL' && <CallResult call={detail.entry.call} overview={overview} />}
            {detail.entry?.kind === 'COMPARISON' && <ComparisonResult comparison={detail.entry.comparison} overview={overview} />}
          </div>
        )}
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
      whatToNotice={noticeModel(history)}
    />
  )
}
