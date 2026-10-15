import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { NOTE } from './faultToleranceModel'
import { NotMeasured } from './MeasurementsPanel'

const NOT_ASSESSED = {
  NOTHING_ACKNOWLEDGED: 'nothing has been acknowledged yet',
  NO_PRIMARY_STORE: 'no primary store to check against',
}

function Row({ label, children }) {
  return (
    <div className="flex flex-wrap justify-between gap-x-4 gap-y-0.5 py-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="tabular-nums">{children}</dd>
    </div>
  )
}

/**
 * Data loss: updates the client was told had succeeded that the new primary does not hold. For a
 * run, the check taken when the service was restored; before any failover, the live check of the
 * status (`dataLossNow`). A measured 0 is shown as "0 lost"; a null is "Not measured" or "Not
 * assessed", never 0. The Simulated badge appears only when the report says `simulated`, with its
 * reason, and the delay is the one the report carries. The explanation is the served notes.
 *
 * @param {object} props
 * @param {object|null} props.run
 * @param {object|null} props.status
 * @param {string[]} [props.notes]
 */
export function DataLossPanel({ run, status, notes = [] }) {
  const loss = run ? run.dataLoss : status?.dataLossNow ?? null
  const heading = run ? `Data loss in run ${run.runId}, checked when the service was restored` : 'Data loss right now (live check, no failover yet)'
  const missingReason = run ? 'Checked when the service is restored' : 'No primary serves right now'
  const assessed = loss && !loss.notAssessed && typeof loss.lost === 'number'
  const explanation = [notes[NOTE.asyncWindow], notes[NOTE.syncSafe], notes[NOTE.lossCounts]].filter(Boolean)

  return (
    <section data-testid="data-loss-panel" aria-label="Data loss" className="space-y-3 rounded-lg border bg-card p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">{heading}</h3>
        {loss?.simulated && <SimulatedBadge reason={loss.simulatedReason ?? undefined} />}
      </div>

      {!loss && <p className="text-sm"><NotMeasured label={run ? 'Not measured yet' : 'Not assessed'} reason={missingReason} /></p>}
      {loss && !assessed && (
        <p className="text-sm text-muted-foreground">Not assessed: {NOT_ASSESSED[loss.notAssessed] ?? (loss.notAssessed ? loss.notAssessed.toLowerCase().replaceAll('_', ' ') : 'no counts were sent')}.</p>
      )}
      {assessed && (
        <>
          <p data-testid="loss-headline" className="flex items-center gap-2 text-lg font-semibold">
            {loss.lost === 0
              ? <CheckCircle2 aria-hidden="true" className="size-5 text-success" />
              : <AlertTriangle aria-hidden="true" className="size-5 text-warning" />}
            {loss.lost} lost
            <span className="text-sm font-normal text-muted-foreground">
              {loss.lost === 0
                ? 'No update the client was told had succeeded is missing.'
                : `${loss.lost === 1 ? 'update' : 'updates'} the client was told had succeeded ${loss.lost === 1 ? 'is' : 'are'} missing from the new primary.`}
            </span>
          </p>
          <dl className="divide-y divide-border/60 text-sm">
            <Row label="Acknowledged (told it succeeded)">{loss.acknowledged}</Row>
            <Row label="Lost">{loss.lost}</Row>
            <Row label="Lost, sent in synchronous mode">{loss.lostSynchronous ?? <NotMeasured reason="Not sent" />}</Row>
            <Row label="Lost, sent in asynchronous mode">{loss.lostAsynchronous ?? <NotMeasured reason="Not sent" />}</Row>
            <Row label="Lost keys">
              {loss.lostKeys?.length
                ? <span className="font-mono text-xs">{loss.lostKeys.join(', ')}</span>
                : 'None'}
            </Row>
            {loss.simulated && typeof loss.simulatedDelayMillis === 'number' && (
              <Row label="Simulated delay before each push">{loss.simulatedDelayMillis} ms</Row>
            )}
          </dl>
        </>
      )}

      {explanation.length > 0 && (
        <div className="space-y-1.5 border-t pt-3 text-xs text-muted-foreground">
          <p className="font-medium text-foreground">Why asynchronous mode can lose confirmed updates</p>
          {explanation.map((note) => <p key={note}>{note}</p>)}
        </div>
      )}
    </section>
  )
}
