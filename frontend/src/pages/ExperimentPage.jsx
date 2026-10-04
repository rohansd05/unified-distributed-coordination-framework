import { useParams } from 'react-router-dom'
import { LabBadge } from '@/components/LabBadge'
import { PageHeader } from '@/components/PageHeader'
import { findBySlug } from '@/lib/experiments'
import { NotFoundPage } from '@/pages/NotFoundPage'

/** The sections every experiment page has, in order (docs/HANDOFF.md 8.4). */
const SECTIONS = ['How it works', 'Controls', 'Live visualisation', 'Measurements', 'Event log', 'What to notice']

/** One page for all ten experiments; an unknown slug is a 404. */
export function ExperimentPage() {
  const { slug } = useParams()
  const experiment = findBySlug(slug)
  if (!experiment) {
    return <NotFoundPage />
  }

  return (
    <>
      <PageHeader title={experiment.title} description={`This module arrives in Phase ${experiment.arrivesIn}.`}>
        <LabBadge lab={experiment.lab} className="h-8 min-w-8 bg-secondary text-sm" />
      </PageHeader>
      <p className="-mt-6 font-mono text-xs text-muted-foreground">module id: {experiment.id}</p>

      {SECTIONS.map((section) => {
        const headingId = `section-${section.toLowerCase().replaceAll(' ', '-')}`
        return (
          <section key={section} aria-labelledby={headingId} className="space-y-2">
            <h2 id={headingId} className="text-lg font-semibold">{section}</h2>
            <div className="rounded-lg border border-dashed bg-card p-6 text-sm text-muted-foreground">
              Not built yet.
            </div>
          </section>
        )
      })}
    </>
  )
}
