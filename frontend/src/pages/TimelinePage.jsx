import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function TimelinePage() {
  return (
    <>
      <PageHeader
        title="Timeline"
        description="The global, causally ordered event log, filtered by module and node."
      />
      <ComingSoon when="Arrives in Phase 13" />
    </>
  )
}
