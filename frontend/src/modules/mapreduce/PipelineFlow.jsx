import { useEffect, useRef } from 'react'
import { CheckCircle2, CircleX, Loader2, RotateCcw, XCircle } from 'lucide-react'
import { cn } from '@/lib/utils'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { runStateLabel, taskTypeLabel } from './labels'
import { prefersReducedMotion } from './motion'
import { crashSentence, pipelineStages, retryArrows, runStatusText } from './runModel'

const STATE_ICON = { RUNNING: Loader2, COMPLETED: CheckCircle2, FAILED: XCircle }
const STATE_TONE = { RUNNING: 'text-primary', COMPLETED: 'text-success', FAILED: 'text-destructive' }

function StateBadge({ state }) {
  const Icon = STATE_ICON[state] ?? Loader2
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-sm font-medium', STATE_TONE[state] ?? 'text-muted-foreground')}>
      <Icon aria-hidden="true" className={cn('size-4', state === 'RUNNING' && 'animate-spin motion-reduce:animate-none')} />
      {runStateLabel(state)}
    </span>
  )
}

/** One worker chip in the map or reduce lane: node id, tasks it ran, and a crash mark (icon and text). */
function WorkerChip({ lane }) {
  const tasks = lane.tasks === null ? '—' : lane.tasks
  return (
    <li
      aria-label={`Node ${lane.nodeId}: ${tasks === '—' ? 'tasks not known yet' : `${tasks} ${tasks === 1 ? 'task' : 'tasks'}`}${lane.crashed ? ', crashed during this run' : ''}`}
      className={cn(
        'flex items-center gap-1.5 rounded-full border bg-navy/60 px-2.5 py-1 font-mono text-[11px]',
        lane.crashed && 'border-dashed border-destructive/80 text-destructive',
      )}
    >
      {lane.crashed && <CircleX aria-hidden="true" className="size-3" />}
      <span>N{lane.nodeId}</span>
      <span className="text-muted-foreground">×{tasks}</span>
      {lane.crashed && <span className="font-sans">crashed</span>}
    </li>
  )
}

/**
 * The page's bold element (R17): the run as a chain of pipeline nodes, Input, Split, Map (one chip
 * per worker), Combine, Shuffle, Partition, Reduce (one chip per worker) and Result, each with the
 * count and the time the backend measured; "—" where nothing was measured. Retries are drawn as
 * dashed links from the node whose attempt failed to the node that re-ran the task, with the same
 * fact in text.
 *
 * Motion only in response to an action: when a run this page saw RUNNING finishes, the stages
 * appear one after another; never on first load and never under prefers-reduced-motion. The run
 * state is shown with an icon and words, and announced politely (at most every two seconds).
 *
 * @param {object} props
 * @param {object|null} props.run the shown RunDto
 */
export function PipelineFlow({ run }) {
  const seenRunningRef = useRef(new Set())
  const runningId = run?.state === 'RUNNING' ? run.runId : null
  useEffect(() => {
    if (runningId) {
      seenRunningRef.current.add(runningId)
    }
  }, [runningId])
  const status = runStatusText(run)
  const announcement = usePoliteAnnouncement(status)
  const stages = pipelineStages(run)
  const arrows = retryArrows(run)
  const crash = crashSentence(run)
  const animate = Boolean(run) && run.state !== 'RUNNING' && seenRunningRef.current.has(run.runId) && !prefersReducedMotion()

  return (
    <figure data-testid="pipeline-flow" className="relative rounded-xl border bg-gradient-to-br from-navy/60 to-card p-5 shadow-inner">
      <figcaption className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <span className="space-y-1">
          <span className="block text-base font-semibold">The run through the pipeline</span>
          <span className="block text-sm text-muted-foreground" data-testid="pipeline-status">{status}</span>
        </span>
        {run && <StateBadge state={run.state} />}
      </figcaption>
      <p aria-live="polite" className="sr-only" data-testid="pipeline-live">{announcement}</p>

      {run && run.coordinatorId !== null && run.coordinatorId !== undefined && (
        <p className="mb-3 text-xs text-muted-foreground">
          Coordinator: <span className="font-mono text-foreground">node {run.coordinatorId}</span>
          {Array.isArray(run.workerIds) && <> · Workers: <span className="font-mono text-foreground">{run.workerIds.join(', ') || '—'}</span></>}
        </p>
      )}

      <ol aria-label="Pipeline stages" key={`${run?.runId ?? 'none'}-${run?.state ?? ''}`} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {stages.map((stage, index) => (
          <li
            key={stage.id}
            data-testid={`stage-${stage.id}`}
            aria-label={`${stage.label}: ${stage.value} ${stage.detail}${stage.timing && stage.timing !== '—' ? `, ${stage.timing}` : ''}`}
            className={cn(
              'relative rounded-lg border border-primary/30 bg-card/80 p-3',
              animate && 'animate-in fade-in-0 slide-in-from-left-2 fill-mode-both duration-300',
            )}
            style={animate ? { animationDelay: `${index * 90}ms` } : undefined}
          >
            <span aria-hidden="true" className="absolute -left-1.5 top-4 size-3 rounded-full border-2 border-primary bg-background" />
            <p className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{index + 1}. {stage.label}</span>
              {stage.timing && <span className="font-mono">{stage.timing}</span>}
            </p>
            <p className="mt-1 truncate text-xl font-semibold tabular-nums" title={stage.value}>{stage.value}</p>
            <p className="text-xs text-muted-foreground">{stage.detail}</p>
            {stage.lanes && stage.lanes.length > 0 && (
              <ul aria-label={`${stage.label} tasks per worker`} className="mt-2 flex flex-wrap gap-1.5">
                {stage.lanes.map((lane) => <WorkerChip key={lane.nodeId} lane={lane} />)}
              </ul>
            )}
          </li>
        ))}
      </ol>

      {crash && (
        <p data-testid="pipeline-crash" className="mt-4 flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <CircleX aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-destructive" />
          <span>{crash}</span>
        </p>
      )}

      {arrows.length > 0 && (
        <ul aria-label="Retried tasks" data-testid="pipeline-retries" className="mt-3 space-y-1.5">
          {arrows.map((arrow) => (
            <li key={arrow.key} className="flex flex-wrap items-center gap-2 text-sm">
              <RotateCcw aria-hidden="true" className="size-4 text-warning" />
              <span className="font-mono text-xs">N{arrow.fromNode}</span>
              <svg aria-hidden="true" width="48" height="10" className="text-warning">
                <line x1="2" y1="5" x2="40" y2="5" stroke="currentColor" strokeWidth="2" strokeDasharray="4 3" />
                <polygon points="40,1 47,5 40,9" fill="currentColor" />
              </svg>
              <span className="font-mono text-xs">N{arrow.toNode}</span>
              <span>
                {taskTypeLabel(arrow.taskType)} task {arrow.taskNumber}: the attempt on node {arrow.fromNode} failed, node {arrow.toNode} re-ran it.
              </span>
            </li>
          ))}
        </ul>
      )}

      {run?.state === 'FAILED' && (
        <p role="alert" className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          <span className="font-semibold text-destructive">Failed.</span> {run.error ?? 'The backend gave no reason.'}
        </p>
      )}
    </figure>
  )
}
