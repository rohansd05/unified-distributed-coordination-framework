import { useEffect, useRef } from 'react'
import { ExperimentLayout } from '@/components/experiment/ExperimentLayout'
import { Button } from '@/components/ui/button'
import { useCluster } from '@/hooks/useCluster'
import { useToast } from '@/hooks/use-toast'
import { usePoliteAnnouncement } from '@/modules/multithreading/usePoliteAnnouncement'
import { DataLossPanel } from './DataLossPanel'
import { FailoverTimeline } from './FailoverTimeline'
import { describeFaultProblem, faultToleranceApi } from './faultToleranceApi'
import { controlsModel, situationText, uiState, words } from './faultToleranceModel'
import { FaultToleranceControls } from './FaultToleranceControls'
import { MeasurementsPanel } from './MeasurementsPanel'
import { ReplicaRail } from './ReplicaRail'
import { SplitBrainPanel } from './SplitBrainPanel'
import { useFaultTolerance } from './useFaultTolerance'

const WHAT_TO_NOTICE = [
  'Synchronous mode loses nothing: after a failover the data loss reads 0 lost. Asynchronous mode can lose the updates still inside the simulated delay when the primary crashes, and the panel names their keys.',
  'The old primary comes back as a backup: nobody tells it to step down. It asks the others, sees a higher epoch and demotes itself. As the highest node it then wins the election again, copies the store of the primary and takes over at a higher epoch.',
  'Epochs protect the data: backups refuse any update stamped with an older epoch, so the no-two-primaries check stays passed.',
]

/**
 * Experiment 8, Fault Tolerance (docs/HANDOFF.md 7): run a stream of client updates, crash the
 * primary, and watch the detection, the promotion and the restored service on a timeline; read
 * the measured detection, failover, recovery and data loss; switch between synchronous and
 * asynchronous replication; recover the old primary and watch it demote itself; the epoch of
 * every node and the no-two-primaries check stay in view. Built on the shared kit; live through
 * /topic/modules/faulttolerance and /topic/cluster, with polling only while something is moving
 * (useFaultTolerance).
 *
 * Honest by construction: every figure is the backend's, a null is "Not measured" or "Unknown",
 * never 0; the explanation is the served notes, the labels and definitions the served glossary;
 * the only Simulated badges are the asynchronous delay, a simulated data loss and the co-hosting.
 *
 * @param {object} props
 * @param {object} props.experiment the catalog entry
 * @param {object} [props.api] see createFaultToleranceApi; for tests
 */
export function FaultTolerancePage({ experiment, api = faultToleranceApi }) {
  const data = useFaultTolerance({ api })
  const { overview, status, run, loading, error, connection, problem, locked } = data
  const { refresh: refreshCluster } = useCluster()
  const { toast } = useToast()
  const alertRef = useRef(null)
  const state = uiState(status, run)
  const situation = situationText(state, status, run)
  const announcement = usePoliteAnnouncement(situation)
  const controls = controlsModel(status, run, { locked })
  const settings = overview?.settings
  const notes = overview?.notes ?? []

  useEffect(() => {
    if (problem) alertRef.current?.focus()
  }, [problem])

  async function act(action, describe, clusterChanged = false) {
    const result = await action()
    if (!result) return
    toast({ title: describe(result) })
    if (clusterChanged) refreshCluster()
  }

  const hasData = Boolean(overview && status)
  const unreachable = connection === 'reconnecting' || connection === 'error' || (hasData && Boolean(error))

  const controlsSection = (
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
        <div data-testid="loading" aria-busy="true" className="h-40 animate-pulse rounded-lg bg-card/60 motion-reduce:animate-none">
          <span className="sr-only">Loading Experiment 8...</span>
        </div>
      )}
      {!hasData && !loading && (
        <div role="alert" data-testid="load-error" className="rounded-lg border border-destructive/40 bg-card p-5 text-sm">
          <p className="font-semibold text-destructive">Could not load Experiment 8.</p>
          <p className="mt-1 text-muted-foreground">{describeFaultProblem(error)?.message}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={() => data.refresh()}>Try again</Button>
        </div>
      )}
      {hasData && (
        <>
          {status.status === 'BUSY' && !data.pending && (
            <p role="status" data-testid="busy-banner" className="rounded-md border border-warning/60 bg-warning/10 p-3 text-sm">
              The backend is busy{status.actionInProgress ? `: ${status.actionInProgress}` : ''}. An action now is answered as busy until it has finished.
            </p>
          )}
          <FaultToleranceControls status={status} settings={settings} controls={controls} state={state} situation={situation}
            pending={data.pending} awaitingIdle={data.awaitingIdle} problem={problem} alertRef={alertRef}
            onStart={() => act(data.start, (r) => (r.outcome === 'ALREADY_STARTED' ? `Already started. ${r.reason ?? ''}` : 'Experiment 8 started: the first primary is being appointed.'))}
            onStartStream={(rate) => act(() => data.startStream(rate), (r) => `Update stream started: ${words(r.mode)?.toLowerCase()} updates, ${r.ratePerSecond} per second.`)}
            onStopStream={() => act(data.stopStream, () => 'Update stream stopped.')}
            onMode={(mode) => act(() => data.setMode(mode), (r) => `${words(r.mode)} mode applies from the next update.`)}
            onCrash={() => act(data.crashPrimary, (r) => `Node ${r.nodeId} crashed at epoch ${r.epoch}. Watch the timeline.`, true)}
            onRecover={() => act(data.recover, (r) => `Node ${r.nodeId} is back; it asks the others who the primary is.`, true)} />
        </>
      )}
    </div>
  )

  const visualisation = hasData ? (
    <div className="space-y-5">
      <ReplicaRail status={status} run={run} settings={settings} notes={notes} />
      <FailoverTimeline run={run} settings={settings} glossary={overview.glossary} />
      <SplitBrainPanel splitBrain={status.splitBrain} />
    </div>
  ) : (
    <div className="h-48 rounded-lg border border-dashed bg-card/40" aria-hidden="true" />
  )

  const measurements = hasData ? (
    <div className="space-y-4">
      <MeasurementsPanel run={run} glossary={overview.glossary} settings={settings} />
      <DataLossPanel run={run} status={status} notes={notes} />
    </div>
  ) : null

  return (
    <ExperimentLayout
      experiment={experiment}
      howItWorks={notes.length > 0
        ? <ol className="list-decimal space-y-1.5 pl-5">{notes.map((note) => <li key={note}>{note}</li>)}</ol>
        : <p className="text-muted-foreground">The explanation loads with the experiment.</p>}
      controls={controlsSection}
      visualisation={visualisation}
      measurements={measurements}
      whatToNotice={WHAT_TO_NOTICE}
    />
  )
}
