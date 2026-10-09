import { useEffect, useRef, useState } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { MetricCard } from '@/components/experiment/MetricCard'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { useStomp } from '@/hooks/useStomp'
import { useToast } from '@/hooks/use-toast'
import { EventLogDownload } from './EventLogDownload'
import { formatMillis } from './labels'
import { mapReduceApi } from './mapReduceApi'
import { PipelineFlow } from './PipelineFlow'
import { ResultsPanel } from './ResultsPanel'
import { RunControls } from './RunControls'
import { RunHistory } from './RunHistory'
import { actionErrorFrom, downNodes, failedAttemptCount, isTerminal, noticeCallouts, num, overviewModel } from './runModel'
import { TaskTable } from './TaskTable'
import { readUpload, UploadError } from './uploadFile'
import { useMapReduce } from './useMapReduce'

const HOW_IT_WORKS = [
  'MapReduce runs one big job on many workers. The coordinator splits the input into one block of lines per worker and sends each block over TCP to a worker as a map task.',
  'Map: each worker turns its lines into key-value pairs, for example one pair (word, 1) for every word. Combine: before sending anything back, the worker adds up the pairs with the same key, so fewer pairs cross the network.',
  'Shuffle and partition: the coordinator groups every value by key and gives each key to one of R reducers by hashing it (the hash of the key modulo R), so a key always lands on the same reducer. Reduce: each reducer adds up its keys and sends back the final values.',
  'Retry: if a worker does not answer (it crashed, refused the connection or took longer than the task timeout), the coordinator sends the same task to the next worker. The task is a pure function of its input, so re-running it gives the same answer.',
  'The average latency job never sends an average. It carries a sum and a count, "sum;count", through map, combine and reduce, and divides once at the end: averages are not associative, so an average of averages would be wrong.',
]

/**
 * Experiment 7, MapReduce (docs/HANDOFF.md 7): run one of three jobs on the bundled sample, an
 * uploaded .txt file or the live cluster event log (link L5), optionally crashing a worker during
 * the run, and see every stage, task and retry. Built on the shared kit; live through
 * /topic/modules/mapreduce and polling of the shown run (see useMapReduce).
 *
 * Honest by construction: every number comes from the backend's run report, an unavailable value
 * is "—" and never 0, nothing is simulated, and the callouts read the real latest run; a result is
 * called identical to an earlier one only when that is proven (see compareResults).
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry (lab, id, title, concept)
 * @param {object} [props.api] see createMapReduceApi; for tests
 */
export function MapReducePage({ experiment, api = mapReduceApi }) {
  const { overview, runs, run, pinnedRunId, loading, error, cleared, comparison, refresh, showAccepted, selectRun } = useMapReduce({ api })
  const { cluster, refresh: refreshCluster } = useCluster()
  const { status: connection } = useStomp()
  const { toast } = useToast()
  const [pending, setPending] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})
  const [actionError, setActionError] = useState(null)
  const mountedRef = useRef(true)
  const seenRunningRef = useRef(new Set())
  const toastedRef = useRef(new Set())

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // A run this page saw RUNNING gets one toast when it ends (HANDOFF 8.5 success state).
  useEffect(() => {
    if (!run) return
    if (run.state === 'RUNNING') {
      seenRunningRef.current.add(run.runId)
      return
    }
    if (isTerminal(run.state) && seenRunningRef.current.has(run.runId) && !toastedRef.current.has(run.runId)) {
      toastedRef.current.add(run.runId)
      const title = run.jobTitle ?? run.jobId
      if (run.state === 'COMPLETED') {
        const total = formatMillis(num(run.report?.timings?.totalMillis))
        toast({ title: `${title} completed${total === null ? '' : ` in ${total} ms`}` })
      } else {
        toast({ variant: 'destructive', title: `${title} failed`, description: run.error ?? undefined })
      }
    }
  }, [run, toast])

  const model = overviewModel(overview)
  const down = downNodes(cluster)
  const busy = model.status === 'BUSY' || run?.state === 'RUNNING'
  const unreachable = connection === 'reconnecting' || connection === 'error' || Boolean(error)
  const report = run?.state === 'COMPLETED' || run?.state === 'FAILED' ? run.report : null
  const timings = report?.timings ?? null

  const startRun = async ({ jobId, inputType, file, crashWorkerId }) => {
    setPending('run')
    setFieldErrors({})
    setActionError(null)
    try {
      const upload = file ? await readUpload(file) : null
      const accepted = await api.startRun({ jobId, inputType, upload, crashWorkerId })
      if (!mountedRef.current) return
      showAccepted(accepted)
      toast({ title: `${accepted?.jobTitle ?? jobId} started` })
    } catch (err) {
      if (!mountedRef.current) return
      if (err instanceof UploadError) {
        setFieldErrors({ 'upload.contentBase64': err.message })
        setActionError({ status: null, title: 'File not sent', detail: err.message })
        return
      }
      const refused = actionErrorFrom(err)
      setFieldErrors(refused.fields)
      setActionError(refused)
      toast({ variant: 'destructive', title: refused.title, description: refused.detail })
      refresh()
    } finally {
      if (mountedRef.current) setPending(null)
    }
  }

  const recover = async (nodeId) => {
    setPending(`recover-${nodeId}`)
    try {
      await api.recoverNode(nodeId)
      if (!mountedRef.current) return
      toast({ title: `Node ${nodeId} recovered for every experiment` })
      refreshCluster()
      refresh()
    } catch (err) {
      if (!mountedRef.current) return
      toast({ variant: 'destructive', title: err?.title || 'Recover failed', description: err?.detail || err?.message })
    } finally {
      if (mountedRef.current) setPending(null)
    }
  }

  const controls = (
    <div className="space-y-6">
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
      <RunControls
        overview={overview}
        downNodeIds={down}
        busy={busy}
        pending={pending}
        fieldErrors={fieldErrors}
        actionError={actionError}
        onRun={startRun}
        onRecover={recover}
      />
      <RunHistory runs={runs} jobs={model.jobs} selectedRunId={run?.runId ?? null} pinnedRunId={pinnedRunId} onSelect={selectRun} />
    </div>
  )

  const visualisation = (
    <div className="space-y-3">
      {!overview && loading && <div className="h-64 animate-pulse rounded-xl bg-card/60 motion-reduce:animate-none" aria-busy="true" />}
      {!overview && !loading && error && (
        <div role="alert" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load the MapReduce data.</p>
          <p className="mt-1 text-muted-foreground">{error.detail || error.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={refresh}>Try again</Button>
        </div>
      )}
      {overview && <PipelineFlow run={run} />}
      {overview && !run && !cleared && (
        <p data-testid="empty-state" className="text-sm text-muted-foreground">
          No run yet. Pick a job and an input above, then select Run; the bundled sample text is the quickest start.
        </p>
      )}
      {cleared && (
        <p data-testid="run-cleared" className="text-sm text-muted-foreground">
          The run that was shown is no longer kept by the backend (a cluster reset clears the run history).
        </p>
      )}
      {run?.notice && (
        <p data-testid="run-notice" className="rounded-md border-l-4 border-warning bg-card px-4 py-3 text-sm">{run.notice}</p>
      )}
    </div>
  )

  const measurements = (
    <div className="space-y-6">
      <p className="text-xs text-muted-foreground">
        {report ? `From run ${run.runId}: ${run.jobTitle ?? run.jobId} on ${run.inputName ?? '—'}.` : 'Nothing has finished yet, so nothing is shown.'}
      </p>
      <div className="grid grid-cols-2 divide-x divide-y rounded-lg border bg-card sm:grid-cols-5 sm:divide-y-0">
        <MetricCard label="Total time" value={formatMillis(num(timings?.totalMillis))} unit="ms" hint="The whole pipeline" />
        <MetricCard label="Map stage" value={formatMillis(num(timings?.mapMillis))} unit="ms" />
        <MetricCard label="Shuffle stage" value={formatMillis(num(timings?.shuffleMillis))} unit="ms" />
        <MetricCard label="Reduce stage" value={formatMillis(num(timings?.reduceMillis))} unit="ms" />
        <MetricCard label="Combiner saving" value={num(report?.combinerSavingPercent)} unit="%" hint="Fewer pairs sent to the shuffle" />
        <MetricCard label="Map tasks" value={num(report?.mapTasks)} />
        <MetricCard label="Reduce tasks" value={num(report?.partitions)} hint={report ? `R = ${num(report.reducers) ?? '—'} reducers` : undefined} />
        <MetricCard label="Retried tasks" value={num(report?.retriedTasks)} hint="Completed on another worker" />
        <MetricCard label="Failed attempts" value={report ? failedAttemptCount(run) : null} />
        <MetricCard label="Result keys" value={num(report?.resultKeys)} />
      </div>
      {run?.inputType === 'EVENT_LOG' && report && (
        <p className="text-xs text-muted-foreground" data-testid="lines-dropped">
          Older event-log lines left out by the byte cap: {num(report.inputLinesDropped) ?? '—'}.
        </p>
      )}

      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Results</h3>
        <ResultsPanel run={run} rowsMax={model.limits?.resultRowsMax ?? null} />
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Every task attempt</h3>
        <TaskTable run={run?.state === 'RUNNING' ? null : run} />
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Event log file</h3>
        <EventLogDownload api={api} />
      </div>
      {model.notes.length > 0 && (
        <div className="space-y-1 text-xs leading-relaxed text-muted-foreground">
          {model.notes.map((note) => <p key={note}>{note}</p>)}
        </div>
      )}
    </div>
  )

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={HOW_IT_WORKS.map((sentence) => <p key={sentence}>{sentence}</p>)}
      controls={controls}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={noticeCallouts(run, comparison)}
    />
  )
}
