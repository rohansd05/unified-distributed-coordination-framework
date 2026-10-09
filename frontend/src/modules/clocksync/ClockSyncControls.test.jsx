import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/services/api'
import { ClockSyncControls } from './ClockSyncControls'

describe('ClockSyncControls', () => {
  afterEach(() => {
    cleanup()
  })
  const sampleOverview = {
    status: 'IDLE',
    actionInProgress: null,
    timeDaemonNodeId: 1,
    nodes: [
      { nodeId: 1, status: 'UP', lamportValue: 5, simulatedDrift: { offsetMillis: 0, driftRateMsPerSec: 0 } },
      { nodeId: 2, status: 'UP', lamportValue: 5, simulatedDrift: { offsetMillis: 100, driftRateMsPerSec: 1.5 } },
      { nodeId: 3, status: 'CRASHED', lamportValue: 2, simulatedDrift: { offsetMillis: -100, driftRateMsPerSec: -1.0 } },
    ],
    limits: {
      defaultTrafficSeconds: 5,
      maxTrafficSeconds: 30,
      defaultMessagesPerSecond: 4,
      maxMessagesPerSecond: 20,
    },
  }

  function makeApi() {
    return {
      recordLocalEvent: vi.fn().mockResolvedValue({ nodeId: 1, lamportTime: 6, description: 'Test event' }),
      sendMessage: vi.fn().mockResolvedValue({
        from: 1,
        to: 2,
        messageId: 10,
        lamportTime: 7,
        payload: 'Ping',
        deliveryStatus: 'SENT',
        deliveryNote: 'Sent via UDP',
      }),
      startTraffic: vi.fn().mockResolvedValue({
        sessionId: 'abc',
        seconds: 5,
        messagesPerSecond: 4,
        status: 'ACCEPTED',
      }),
      runBerkeleyRound: vi.fn().mockResolvedValue({
        roundId: 1,
        daemonNodeId: 1,
        outlierThresholdMillis: 400,
        status: 'ACCEPTED',
      }),
      updateDrift: vi.fn().mockResolvedValue({
        nodeId: 2,
        offsetMillis: 150,
        driftRateMsPerSec: 2.0,
      }),
    }
  }

  it('submits a local event and moves focus to result panel', async () => {
    const api = makeApi()
    const onAnnounce = vi.fn()
    const onActionDone = vi.fn()

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={onAnnounce}
        onActionDone={onActionDone}
      />
    )

    const btn = screen.getByRole('button', { name: 'Record local event' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.recordLocalEvent).toHaveBeenCalledWith(1, { description: undefined }))
    expect(onAnnounce).toHaveBeenCalledWith(expect.stringContaining('Local event recorded on Node 1'))
    expect(onActionDone).toHaveBeenCalled()

    const result = await screen.findByTestId('action-result')
    expect(result).toBeTruthy()
    expect(result.textContent).toContain('Lamport clock advanced to 6')
    expect(document.activeElement).toBe(result)
  })

  it('submits a message and shows delivery status with focus transfer', async () => {
    const api = makeApi()
    const onAnnounce = vi.fn()

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={onAnnounce}
        onActionDone={vi.fn()}
      />
    )

    const btn = screen.getByRole('button', { name: 'Send message' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.sendMessage).toHaveBeenCalled())
    expect(onAnnounce).toHaveBeenCalledWith(expect.stringContaining('Delivery status is Sent'))

    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Sent message from Node 1 to Node 2')
    expect(document.activeElement).toBe(result)
  })

  it('starts random traffic burst using overview limits', async () => {
    const api = makeApi()
    const onAnnounce = vi.fn()

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={onAnnounce}
        onActionDone={vi.fn()}
      />
    )

    const btn = screen.getByRole('button', { name: 'Start traffic burst' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.startTraffic).toHaveBeenCalledWith({ seconds: 5, messagesPerSecond: 4 }))
    expect(onAnnounce).toHaveBeenCalledWith(expect.stringContaining('Random traffic session accepted'))
  })

  it('submits Berkeley synchronization round in Berkeley tab', async () => {
    const api = makeApi()
    const onAnnounce = vi.fn()

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={onAnnounce}
        onActionDone={vi.fn()}
      />
    )

    // Switch to Berkeley tab
    fireEvent.click(screen.getByRole('tab', { name: 'Berkeley physical sync' }))

    const btn = screen.getByRole('button', { name: 'Run synchronization round' })
    fireEvent.click(btn)

    await waitFor(() => expect(api.runBerkeleyRound).toHaveBeenCalledWith({ outlierThresholdMillis: 400 }))
    expect(onAnnounce).toHaveBeenCalledWith(expect.stringContaining('Berkeley synchronization round accepted'))
  })

  it('handles 400 validation error and moves focus to alert', async () => {
    const api = makeApi()
    api.startTraffic.mockRejectedValue(
      new ApiError({
        status: 400,
        title: 'Invalid request parameters',
        detail: 'Invalid parameter: seconds',
        errors: { seconds: 'must be between 1 and 30, was 50' },
      })
    )

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={vi.fn()}
        onActionDone={vi.fn()}
      />
    )

    fireEvent.click(screen.getByRole('button', { name: 'Start traffic burst' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toBeTruthy()
    expect(alert.textContent).toContain('Invalid parameter: seconds')
    expect(screen.getByText('must be between 1 and 30, was 50')).toBeTruthy()
    expect(document.activeElement).toBe(alert)
  })

  it('handles 409 node down error', async () => {
    const api = makeApi()
    api.sendMessage.mockRejectedValue(
      new ApiError({
        status: 409,
        title: 'Node down',
        detail: 'Node 3 is crashed',
        nodeId: 3,
      })
    )

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={vi.fn()}
        onActionDone={vi.fn()}
      />
    )

    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Node 3 is crashed')
    expect(alert.textContent).toContain('Associated node: Node 3')
    expect(document.activeElement).toBe(alert)
  })

  it('submits drift offset update with right node id and value, announces politely and moves focus to result', async () => {
    const api = makeApi()
    const onAnnounce = vi.fn()

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={onAnnounce}
        onActionDone={vi.fn()}
      />
    )

    // Switch to Berkeley tab
    fireEvent.click(screen.getByRole('tab', { name: 'Berkeley physical sync' }))

    // Change offset
    const offsetInput = screen.getByLabelText('Offset (ms)')
    fireEvent.change(offsetInput, { target: { value: '250' } })

    const submitBtn = screen.getByRole('button', { name: 'Update drift parameters' })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(api.updateDrift).toHaveBeenCalledWith(2, {
        initialOffsetMillis: 250,
        driftRateMsPerSec: 1.5,
      })
    })

    expect(onAnnounce).toHaveBeenCalledWith(expect.stringContaining('Simulated drift updated for Node 2'))

    const result = await screen.findByTestId('action-result')
    expect(result.textContent).toContain('Node 2 drift set to +150 ms')
    expect(document.activeElement).toBe(result)
  })

  it('shows 400 validation error message beside the drift field', async () => {
    const api = makeApi()
    api.updateDrift.mockRejectedValue(
      new ApiError({
        status: 400,
        title: 'Invalid request parameters',
        detail: 'Invalid parameter: initialOffsetMillis',
        errors: { initialOffsetMillis: 'must be between -5000 and 5000 ms' },
      })
    )

    render(
      <ClockSyncControls
        overview={sampleOverview}
        api={api}
        onAnnounce={vi.fn()}
        onActionDone={vi.fn()}
      />
    )

    fireEvent.click(screen.getByRole('tab', { name: 'Berkeley physical sync' }))
    fireEvent.click(screen.getByRole('button', { name: 'Update drift parameters' }))

    await waitFor(() => {
      expect(screen.getByText('must be between -5000 and 5000 ms')).toBeTruthy()
    })
  })
})

