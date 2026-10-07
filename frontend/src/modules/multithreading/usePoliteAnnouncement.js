import { useEffect, useRef, useState } from 'react'

export const ANNOUNCE_INTERVAL_MS = 2000

/**
 * Text for an aria-live="polite" region that changes at most once every intervalMs, so a
 * screen reader hears the latest state without an announcement on every one-second tick.
 * The first value is announced at once; later changes wait out the interval and then
 * announce whatever is current. The pending timer is cleared on unmount.
 */
export function usePoliteAnnouncement(text, intervalMs = ANNOUNCE_INTERVAL_MS) {
  const [announced, setAnnounced] = useState(text)
  const lastAnnouncedAtRef = useRef(0)

  useEffect(() => {
    if (text === announced) {
      return undefined
    }
    const wait = Math.max(0, lastAnnouncedAtRef.current + intervalMs - Date.now())
    const timer = setTimeout(() => {
      lastAnnouncedAtRef.current = Date.now()
      setAnnounced(text)
    }, wait)
    return () => clearTimeout(timer)
  }, [text, announced, intervalMs])

  return announced
}
