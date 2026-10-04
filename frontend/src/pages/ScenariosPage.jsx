import { ComingSoon } from '@/components/ComingSoon'
import { PageHeader } from '@/components/PageHeader'

export function ScenariosPage() {
  return (
    <>
      <PageHeader
        title="Scenarios"
        description="Guided demonstrations that span several modules."
      />
      <ComingSoon when="Arrives in Phase 13" />
    </>
  )
}
