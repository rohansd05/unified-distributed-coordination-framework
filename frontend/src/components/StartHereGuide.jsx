import { useState } from 'react'
import { Link } from 'react-router-dom'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'

export const START_HERE_KEY = 'udcf.startHere.dismissed'

// Storage can be missing or throw (private mode, blocked site data): the guide still works,
// it just comes back on the next visit.
function readDismissed() {
  try {
    return window.localStorage.getItem(START_HERE_KEY) === 'true'
  } catch {
    return false
  }
}

function saveDismissed() {
  try {
    window.localStorage.setItem(START_HERE_KEY, 'true')
  } catch {
    // Not remembered; dismissed for this visit only.
  }
}

const linkClass = 'font-medium text-primary underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring'

const STEPS = [
  {
    title: 'Look at the map',
    body: <>Below, every experiment shares the same nodes. Select a link on the map to see the live evidence that one experiment relies on another.</>,
  },
  {
    title: 'Open an experiment',
    body: <>Pick one from the sidebar, for example <Link to="/experiments/4-election" className={linkClass}>Experiment 4, Bully and Ring Election</Link>, and crash the leader.</>,
  },
  {
    title: 'Run a scenario',
    body: <>A <Link to="/scenarios" className={linkClass}>scenario</Link> drives the whole cluster through an incident and explains each step as it happens.</>,
  },
]

/** First-visit guide on Overview (14.1): three short steps, dismissible, remembered in localStorage. */
export function StartHereGuide() {
  const [dismissed, setDismissed] = useState(readDismissed)
  if (dismissed) return null

  const dismiss = () => {
    saveDismissed()
    setDismissed(true)
  }

  return (
    <section aria-labelledby="start-here-heading" data-testid="start-here" className="relative rounded-lg border border-primary/40 bg-card p-4 sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <h2 id="start-here-heading" className="text-lg font-semibold">Start here</h2>
        <Button variant="ghost" size="icon" className="-mr-2 -mt-1 shrink-0" aria-label="Dismiss the start here guide" onClick={dismiss}>
          <X aria-hidden="true" />
        </Button>
      </div>
      <ol className="mt-3 grid gap-4 md:grid-cols-3">
        {STEPS.map((step, index) => (
          <li key={step.title} className="flex gap-3">
            <span aria-hidden="true" className="flex size-7 shrink-0 items-center justify-center rounded-full border-2 border-primary font-mono text-xs font-semibold text-primary">
              {index + 1}
            </span>
            <div className="min-w-0 text-sm">
              <h3 className="font-medium text-foreground">{step.title}</h3>
              <p className="mt-0.5 text-muted-foreground">{step.body}</p>
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}
