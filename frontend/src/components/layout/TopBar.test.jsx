import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { TopBar } from './TopBar'
import { StompContext } from '@/services/stomp/StompContext'
import { ClusterContext } from '@/services/cluster/ClusterContext'
import clusterWithLeader from '@/test/fixtures/election/cluster-with-leader.json'
import clusterLeaderCrashed from '@/test/fixtures/election/cluster-leader-crashed.json'

afterEach(cleanup)

function renderTopBar({ stomp = {}, cluster = {} } = {}) {
  const stompValue = {
    status: 'connected',
    subscribe: () => () => {},
    onConnect: () => () => {},
    ...stomp,
  }

  const clusterValue = {
    info: { mode: 'local' },
    cluster: { size: 5, upCount: 5, nodes: [] },
    modules: [],
    loading: false,
    error: null,
    refresh: () => {},
    ...cluster,
  }

  return render(
    <StompContext.Provider value={stompValue}>
      <ClusterContext.Provider value={clusterValue}>
        <TopBar menuOpen={false} onToggleMenu={() => {}} />
      </ClusterContext.Provider>
    </StompContext.Provider>,
  )
}

describe('TopBar', () => {
  it('renders "Live" as text when connected', () => {
    renderTopBar({ stomp: { status: 'connected' } })
    expect(screen.getByText('Live')).toBeTruthy()
  })

  it('renders "Reconnecting…" as text when reconnecting', () => {
    renderTopBar({ stomp: { status: 'reconnecting' } })
    expect(screen.getByText('Reconnecting…')).toBeTruthy()
  })

  it('renders "Backend unreachable" as text when disconnected or error', () => {
    renderTopBar({ stomp: { status: 'disconnected' } })
    expect(screen.getByText('Backend unreachable')).toBeTruthy()

    cleanup()

    renderTopBar({ stomp: { status: 'error' } })
    expect(screen.getByText('Backend unreachable')).toBeTruthy()
  })

  it('renders mode when loaded and "—" while loading', () => {
    renderTopBar({ cluster: { loading: false, info: { mode: 'local' } } })
    const modeDt = screen.getByText('Mode')
    expect(modeDt.nextElementSibling.textContent).toBe('local')

    cleanup()

    renderTopBar({ cluster: { loading: true, info: null } })
    const modeDtLoading = screen.getByText('Mode')
    expect(modeDtLoading.nextElementSibling.textContent).toBe('—')
  })

  it('renders "None elected" when no leader role and node id when elected', () => {
    renderTopBar({
      cluster: {
        loading: false,
        cluster: {
          size: 5,
          upCount: 5,
          nodes: [
            { id: 1, roles: [] },
            { id: 2, roles: [] },
          ],
        },
      },
    })
    const leaderDt = screen.getByText('Leader')
    expect(leaderDt.nextElementSibling.textContent).toBe('None elected')

    cleanup()

    renderTopBar({
      cluster: {
        loading: false,
        cluster: {
          size: 5,
          upCount: 5,
          nodes: [
            { id: 1, roles: [] },
            { id: 2, roles: ['LEADER'] },
          ],
        },
      },
    })
    const leaderDtElected = screen.getByText('Leader')
    expect(leaderDtElected.nextElementSibling.textContent).toBe('2')
  })

  it('shows the leader from the real GET /api/cluster captures, and "None elected" after it crashed', () => {
    renderTopBar({ cluster: { loading: false, cluster: clusterWithLeader } })
    expect(screen.getByText('Leader').nextElementSibling.textContent).toBe('5')

    cleanup()

    renderTopBar({ cluster: { loading: false, cluster: clusterLeaderCrashed } })
    expect(screen.getByText('Leader').nextElementSibling.textContent).toBe('None elected')
  })

  it('renders "5/5" and "4/5" node counts, and "—" while loading', () => {
    renderTopBar({
      cluster: {
        loading: false,
        cluster: { size: 5, upCount: 5, nodes: [] },
      },
    })
    const nodesUpDt = screen.getByText('Nodes up')
    expect(nodesUpDt.nextElementSibling.textContent).toBe('5/5')

    cleanup()

    renderTopBar({
      cluster: {
        loading: false,
        cluster: { size: 5, upCount: 4, nodes: [] },
      },
    })
    const nodesUpDt4 = screen.getByText('Nodes up')
    expect(nodesUpDt4.nextElementSibling.textContent).toBe('4/5')

    cleanup()

    renderTopBar({
      cluster: {
        loading: true,
        cluster: null,
      },
    })
    const nodesUpDtLoading = screen.getByText('Nodes up')
    expect(nodesUpDtLoading.nextElementSibling.textContent).toBe('—')
  })
})
