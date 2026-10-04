import { cn } from '@/lib/utils'

/** The lab-number badge shown beside every experiment. Screen readers hear "Lab N". */
export function LabBadge({ lab, className }) {
  return (
    <span
      className={cn(
        'inline-flex h-6 min-w-6 shrink-0 items-center justify-center rounded-md bg-background/60 px-1.5 font-mono text-xs font-semibold text-foreground',
        className,
      )}
    >
      <span className="sr-only">Lab </span>
      {lab}
    </span>
  )
}
