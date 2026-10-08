import { useEffect, useRef } from 'react'
import { SimulatedBadge } from '@/components/experiment/SimulatedBadge'
import { formatMillis, valueText } from './labels'

function prefersReducedMotion() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * The asynchronous replication window: shown exactly while the latest write's replicationState
 * is PENDING, as the backend reports it, and gone once the backend reports COMPLETE. The bar
 * that empties over the simulated delay is decoration only (hidden from assistive technology,
 * still under prefers-reduced-motion); it never opens or closes the window (R7). The delay is
 * simulated, so the window carries the Simulated badge with the backend's reason.
 *
 * @param {object} props
 * @param {ReturnType<import('./replicaModel').pendingWindow>} props.window
 */
export function PendingWindow({ window: replicationWindow }) {
  const barRef = useRef(null)
  const writeId = replicationWindow.write?.writeId ?? null
  const delay = replicationWindow.delayMillis

  useEffect(() => {
    const bar = barRef.current
    if (!bar || !writeId || !delay || typeof bar.animate !== 'function' || prefersReducedMotion()) {
      return undefined
    }
    const animation = bar.animate([{ transform: 'scaleX(1)' }, { transform: 'scaleX(0)' }],
      { duration: delay, easing: 'linear', fill: 'forwards' })
    return () => animation.cancel()
  }, [writeId, delay])

  if (!replicationWindow.open) {
    return null
  }
  const { write } = replicationWindow
  const confirm = formatMillis(write.confirmMillis)

  return (
    <div data-testid="pending-window" className="relative rounded-lg border border-warning/60 bg-warning/10 p-4 text-sm">
      <p className="flex flex-wrap items-center gap-2 font-semibold">
        Replication window open
        <SimulatedBadge reason={replicationWindow.reason ?? undefined} />
      </p>
      <p className="mt-1 text-muted-foreground">
        <span className="font-mono text-foreground">{write.item.key}</span> = {valueText(write.item.value)} was confirmed
        to the client{confirm ? ` after ${confirm} ms` : ''}. Each backup gets it only after a {delay} ms wait, so until
        every backup has answered, a backup may still return the old value.
      </p>
      <div aria-hidden="true" className="mt-3 h-1.5 overflow-hidden rounded-full bg-warning/20">
        <div ref={barRef} className="h-full origin-left rounded-full bg-warning" />
      </div>
    </div>
  )
}
