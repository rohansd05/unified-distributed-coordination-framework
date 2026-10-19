import { useEffect, useState } from 'react'

/**
 * Calm, full-width notice while a sleeping backend starts (14.1). Not an error: free hosting
 * sleeps after 15 minutes without visitors. The seconds counter is hidden from screen readers so
 * the status is announced once, not every second.
 *
 * @param {object} props
 * @param {number} props.since Date.now() when the wait began
 */
export function WakingBanner({ since }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.floor((now - since) / 1000))

  return (
    <div data-testid="waking-banner" className="flex shrink-0 items-center gap-3 border-b border-warning/40 bg-warning/10 px-4 py-3 text-sm">
      <span className="flex shrink-0 items-center gap-1" aria-hidden="true">
        {[0, 1, 2].map((i) => (
          <span key={i} className="size-2 animate-pulse rounded-full bg-warning motion-reduce:animate-none"
            style={{ animationDelay: `${i * 300}ms` }} />
        ))}
      </span>
      <p role="status" className="min-w-0 flex-1">
        The backend is waking up — free hosting sleeps after 15 minutes without visitors. This takes about a minute.
      </p>
      <span data-testid="waking-elapsed" aria-hidden="true" className="shrink-0 font-mono text-muted-foreground">{seconds} s</span>
    </div>
  )
}
