import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function ScenariosPage() {
  return (
    <>
      <PageHeader
        title="Scenarios"
        description="Guided demonstrations that span several modules."
      />
      <ComingSoon when="Arrives in Phase 9A (Experiments 2–8); extended in Phase 13" />
    </>
  )
}
