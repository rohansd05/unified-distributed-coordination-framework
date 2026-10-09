import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/services/api'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewLeaderCrashed from '@/test/fixtures/election/overview-leader-crashed.json'
import overviewTimedOut from '@/test/fixtures/election/overview-ring-timed-out.json'
import startBully from '@/test/fixtures/election/start-bully.json'
import startRing from '@/test/fixtures/election/start-ring.json'
import busyFixture from '@/test/fixtures/election/error-409-module-busy.json'
import nodeDownFixture from '@/test/fixtures/election/error-409-node-down.json'
import { ElectionControls } from './ElectionControls'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

const problem = (body) => new ApiError({ status: body.status, title: body.title, detail: body.detail,
  nodeId: body.nodeId ?? null, moduleId: body.moduleId ?? null })

function setup(overview = overviewAfterBully, api = {}) {
  const props = {
    api: {
      startElection: vi.fn().mockResolvedValue(startBully),
      crashNode: vi.fn().mockResolvedValue({}),
      recoverNode: vi.fn().mockResolvedValue({}),
      ...api,
    },
    onStarted: vi.fn(),
    onChanged: vi.fn(),
    onAnnounce: vi.fn(),
  }
  render(<ElectionControls overview={overview} {...props} />)
  return props
}

describe('ElectionControls', () => {
  it('starts the chosen algorithm from the chosen node with {algorithm, nodeId}', async () => {
    const props = setup(overviewAfterBully, { startElection: vi.fn().mockResolvedValue(startRing) })
    fireEvent.click(screen.getByLabelText(/Ring/))
    fireEvent.change(screen.getByLabelText('Start from node'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start election' }))
    await waitFor(() => expect(props.onStarted).toHaveBeenCalledWith(startRing))
    expect(props.api.startElection).toHaveBeenCalledWith({ algorithm: 'RING', nodeId: 2 })
    expect(props.onChanged).toHaveBeenCalled()
  })

  it('crashes and recovers a node straight away, with no confirmation dialog', async () => {
    const props = setup(overviewLeaderCrashed)
    fireEvent.click(screen.getByRole('button', { name: 'Crash node 4' }))
    await waitFor(() => expect(props.api.crashNode).toHaveBeenCalledWith(4))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Recover node 5' }))
    await waitFor(() => expect(props.api.recoverNode).toHaveBeenCalledWith(5))
    expect(props.onAnnounce).toHaveBeenCalledWith('Node 5 recovered.')
  })

  it.each([
    ['module busy', busyFixture],
    ['node down', nodeDownFixture],
  ])('shows the real 409 %s answer as an alert in the backend\'s words', async (_, fixture) => {
    setup(overviewAfterBully, { startElection: vi.fn().mockRejectedValue(problem(fixture)) })
    fireEvent.click(screen.getByRole('button', { name: 'Start election' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain(fixture.title)
    expect(alert.textContent).toContain(fixture.detail)
  })

  it('disables Start while an election is in progress and says why', () => {
    setup({ ...overviewAfterBully, status: 'BUSY' })
    expect(screen.getByRole('button', { name: 'Start election' }).disabled).toBe(true)
    expect(screen.getByText('An election is in progress. Wait for it to finish.')).toBeTruthy()
  })

  it('warns that Ring cannot elect with one live node, with the timeout from the settings', () => {
    setup(overviewTimedOut)
    expect(screen.queryByText(/Only one node is up/)).toBeNull()
    fireEvent.click(screen.getByLabelText(/Ring/))
    expect(screen.getByText(/Only one node is up/).textContent).toContain('time out after 10 s')
  })

  it('offers crashed nodes as disabled starting points, and every control is a native form control', () => {
    setup(overviewLeaderCrashed)
    const option = screen.getByRole('option', { name: 'Node 5 (crashed)' })
    expect(option.disabled).toBe(true)
    expect(screen.getAllByRole('radio')).toHaveLength(2)
    expect(screen.getByRole('form', { name: 'Start an election' })).toBeTruthy()
  })
})
