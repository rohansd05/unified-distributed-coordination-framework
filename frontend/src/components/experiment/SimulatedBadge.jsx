import { useId } from 'react'
import { cn } from '@/lib/utils'

/**
 * Marks something as simulated (decision R7): the visible word "Simulated", and the reason in
 * a tooltip that opens on hover and on keyboard focus. The reason is also the badge's
 * accessible description, so screen readers announce it without opening the tooltip.
 *
 * The closed tooltip is display: none (not visibility: hidden), so it never widens the page:
 * a hidden but laid-out box would add sideways scrolling on a 375 px screen.
 *
 * @param {object} props
 * @param {string} [props.reason] a plain sentence saying what is simulated and why
 * @param {string} [props.className]
 */
export function SimulatedBadge({ reason, className }) {
  const tooltipId = useId()

  return (
    <span data-testid="simulated-badge" className={cn('group relative inline-flex align-middle', className)}>
      <span
        tabIndex={0}
        aria-describedby={reason ? tooltipId : undefined}
        className="inline-flex items-center rounded border border-warning/60 bg-warning/15 px-1.5 py-0.5 text-[11px] font-medium leading-none text-warning focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        Simulated
      </span>
      {reason && (
        <span
          role="tooltip"
          id={tooltipId}
          className="pointer-events-none absolute left-0 top-full z-30 mt-1.5 hidden w-max max-w-[min(16rem,70vw)] rounded-md border bg-popover p-2.5 text-xs font-normal leading-snug text-popover-foreground shadow-lg group-hover:block group-focus-within:block"
        >
          {reason}
        </span>
      )}
    </span>
  )
}
