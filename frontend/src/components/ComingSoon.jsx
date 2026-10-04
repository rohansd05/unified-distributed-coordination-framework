import { Construction } from 'lucide-react'

/** Placeholder card for a page whose content arrives in a later step. */
export function ComingSoon({ when }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-dashed bg-card p-6 text-muted-foreground">
      <Construction className="size-5 shrink-0" aria-hidden="true" />
      <p>{when}</p>
    </div>
  )
}
