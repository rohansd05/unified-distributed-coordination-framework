import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ClusterPage } from '@/pages/ClusterPage'
import { ApiError } from '@/services/api'
import * as useClusterModule from '@/hooks/useCluster'
import * as useToastModule from '@/hooks/use-toast'
import clusterAfterCrash from '@/test/fixtures/mapreduce/cluster-after-crash.json'

/*
 * E7d requirement: a cluster reset is refused while a MapReduce run is active, and the 409
 * message must reach the user. The Experiment 7 page has no reset control; the reset lives on
 * the Cluster page (not edited here). This checks that the Cluster page shows the backend's 409
 * detail as it is. The error is built inline: no 409 reset body was captured, so none is
 * presented as a fixture. Its wording follows ClusterResetService and ModuleBusyException.
 */

const BUSY_DETAIL = "Module 'mapreduce' is busy: 'a long-running action' is in progress"
let toast

beforeEach(() => {
  toast = vi.fn()
  vi.spyOn(useToastModule, 'useToast').mockReturnValue({ toast, toasts: [], dismiss: vi.fn() })
  vi.spyOn(useClusterModule, 'useCluster').mockReturnValue({
    info: null, cluster: clusterAfterCrash, modules: [], loading: false, error: null, refresh: vi.fn(),
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('Cluster reset while a MapReduce run is active', () => {
  it('shows the backend\'s 409 detail in a destructive toast; nothing hides it', async () => {
    const api = {
      crashNode: vi.fn(),
      recoverNode: vi.fn(),
      resetCluster: vi.fn().mockRejectedValue(new ApiError({ status: 409, title: 'Module busy', detail: BUSY_DETAIL, moduleId: 'mapreduce' })),
    }
    render(<ClusterPage api={api} />)

    fireEvent.click(screen.getByRole('button', { name: 'Reset cluster' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Reset cluster' }).at(-1))

    await waitFor(() => expect(api.resetCluster).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(toast).toHaveBeenCalledWith({ variant: 'destructive', title: 'Reset failed', description: BUSY_DETAIL }))
  })
})
