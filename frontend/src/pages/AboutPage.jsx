import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function AboutPage() {
  return (
    <>
      <PageHeader
        title="About"
        description="Architecture, the mapping to the lab list, and what is real or simulated."
      />
      <ComingSoon when="Arrives in Phase 14" />
    </>
  )
}
