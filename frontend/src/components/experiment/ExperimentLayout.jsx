import { useId, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { LabBadge } from '@/components/LabBadge'
import { PageHeader } from '@/components/PageHeader'
import { cn } from '@/lib/utils'
import { ModuleEventLog } from './ModuleEventLog'

/** A section with an h2, in the fixed order of docs/HANDOFF.md 8.4. */
function Section({ name, title, children, heading }) {
  const headingId = useId()
  return (
    <section data-testid={`section-${name}`} aria-labelledby={headingId} className="space-y-3">
      {heading ? heading(headingId) : (
        <h2 id={headingId} className="text-lg font-semibold">{title}</h2>
      )}
      {children}
    </section>
  )
}

/**
 * The shared shape of every experiment page (docs/HANDOFF.md 8.4): header (lab badge, title,
 * the catalog's optional one-line concept), then How it works (collapsible), Controls, Live
 * visualisation, Measurements, the module's Event log (added here, from experiment.id) and
 * What to notice. Generic: nothing in it is specific to one experiment.
 *
 * Test ids: experiment-layout, and section-<name> for how-it-works, controls, visualisation,
 * measurements, event-log and what-to-notice.
 *
 * @param {object} props
 * @param {{ id: string, lab: number, title: string, concept?: string }} props.experiment
 * @param {import('react').ReactNode} props.howItWorks plain-language explanation
 * @param {import('react').ReactNode} props.controls
 * @param {import('react').ReactNode} props.visualisation the page's one bold element (R17)
 * @param {import('react').ReactNode} props.measurements
 * @param {import('react').ReactNode | string[]} props.whatToNotice two or three callouts; an array becomes a list
 */
export function ExperimentLayout({ experiment, howItWorks, controls, visualisation, measurements, whatToNotice }) {
  const [explanationOpen, setExplanationOpen] = useState(true)
  const explanationId = useId()

  return (
    <div data-testid="experiment-layout" className="space-y-8">
      <PageHeader title={experiment.title} description={experiment.concept}>
        <LabBadge lab={experiment.lab} className="h-8 min-w-8 bg-secondary text-sm" />
      </PageHeader>

      <Section
        name="how-it-works"
        heading={(headingId) => (
          <h2 id={headingId} className="text-lg font-semibold">
            <button
              type="button"
              aria-expanded={explanationOpen}
              aria-controls={explanationId}
              onClick={() => setExplanationOpen((open) => !open)}
              className="inline-flex items-center gap-2 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              How it works
              <ChevronDown
                aria-hidden="true"
                className={cn('size-4 text-muted-foreground', explanationOpen && 'rotate-180')}
              />
            </button>
          </h2>
        )}
      >
        <div id={explanationId} hidden={!explanationOpen} className="max-w-3xl space-y-2 text-sm leading-relaxed text-foreground/90">
          {howItWorks}
        </div>
      </Section>

      <Section name="controls" title="Controls">{controls}</Section>
      <Section name="visualisation" title="Live visualisation">{visualisation}</Section>
      <Section name="measurements" title="Measurements">{measurements}</Section>
      <Section name="event-log" title="Event log">
        <ModuleEventLog moduleId={experiment.id} />
      </Section>
      <Section name="what-to-notice" title="What to notice">
        {Array.isArray(whatToNotice) ? (
          <ul className="space-y-2">
            {whatToNotice.map((callout) => (
              <li key={callout} className="rounded-md border-l-4 border-primary bg-card px-4 py-3 text-sm">
                {callout}
              </li>
            ))}
          </ul>
        ) : (
          whatToNotice
        )}
      </Section>
    </div>
  )
}
