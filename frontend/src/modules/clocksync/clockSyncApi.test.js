import { describe, expect, it, vi } from 'vitest'
import { createClockSyncApi } from './clockSyncApi'

describe('clockSyncApi', () => {
  function makeMockBase() {
    const client = {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
    }
    const getEvents = vi.fn()
    return { client, getEvents }
  }

  it('calls GET /api/modules/clocksync for overview', async () => {
    const base = makeMockBase()
    base.client.get.mockResolvedValue({ data: { status: 'IDLE' } })
    const api = createClockSyncApi(base)

    const res = await api.getOverview()
    expect(base.client.get).toHaveBeenCalledWith('/api/modules/clocksync')
    expect(res).toEqual({ status: 'IDLE' })
  })

  it('calls POST /api/modules/clocksync/nodes/{id}/local-events', async () => {
    const base = makeMockBase()
    base.client.post.mockResolvedValue({ data: { lamportTime: 2 } })
    const api = createClockSyncApi(base)

    const res = await api.recordLocalEvent(1, { description: 'test' })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/clocksync/nodes/1/local-events', { description: 'test' })
    expect(res).toEqual({ lamportTime: 2 })
  })

  it('calls POST /api/modules/clocksync/messages', async () => {
    const base = makeMockBase()
    base.client.post.mockResolvedValue({ data: { deliveryStatus: 'SENT' } })
    const api = createClockSyncApi(base)

    const res = await api.sendMessage({ from: 1, to: 2, payload: 'ping' })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/clocksync/messages', { from: 1, to: 2, payload: 'ping' })
    expect(res).toEqual({ deliveryStatus: 'SENT' })
  })

  it('calls POST /api/modules/clocksync/traffic', async () => {
    const base = makeMockBase()
    base.client.post.mockResolvedValue({ data: { sessionId: 'xyz' } })
    const api = createClockSyncApi(base)

    const res = await api.startTraffic({ seconds: 5, messagesPerSecond: 4 })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/clocksync/traffic', { seconds: 5, messagesPerSecond: 4 })
    expect(res).toEqual({ sessionId: 'xyz' })
  })

  it('calls POST /api/modules/clocksync/berkeley-rounds', async () => {
    const base = makeMockBase()
    base.client.post.mockResolvedValue({ data: { roundId: 1 } })
    const api = createClockSyncApi(base)

    const res = await api.runBerkeleyRound({ outlierThresholdMillis: 400 })
    expect(base.client.post).toHaveBeenCalledWith('/api/modules/clocksync/berkeley-rounds', { outlierThresholdMillis: 400 })
    expect(res).toEqual({ roundId: 1 })
  })

  it('calls PUT /api/modules/clocksync/nodes/{id}/drift', async () => {
    const base = makeMockBase()
    base.client.put.mockResolvedValue({ data: { offsetMillis: 100 } })
    const api = createClockSyncApi(base)

    const res = await api.updateDrift(2, { initialOffsetMillis: 100, driftRateMsPerSec: 1.5 })
    expect(base.client.put).toHaveBeenCalledWith('/api/modules/clocksync/nodes/2/drift', { initialOffsetMillis: 100, driftRateMsPerSec: 1.5 })
    expect(res).toEqual({ offsetMillis: 100 })
  })

  it('calls GET /api/modules/clocksync/verification', async () => {
    const base = makeMockBase()
    base.client.get.mockResolvedValue({ data: { passed: true } })
    const api = createClockSyncApi(base)

    const res = await api.getVerification()
    expect(base.client.get).toHaveBeenCalledWith('/api/modules/clocksync/verification')
    expect(res).toEqual({ passed: true })
  })

  it('calls GET /api/modules/clocksync/timeline with limit', async () => {
    const base = makeMockBase()
    base.client.get.mockResolvedValue({ data: { events: [] } })
    const api = createClockSyncApi(base)

    const res = await api.getTimeline(50)
    expect(base.client.get).toHaveBeenCalledWith('/api/modules/clocksync/timeline', { params: { limit: 50 } })
    expect(res).toEqual({ events: [] })
  })

  it('delegates getEvents to base.getEvents', async () => {
    const base = makeMockBase()
    base.getEvents.mockResolvedValue([])
    const api = createClockSyncApi(base)

    const res = await api.getEvents({ limit: 10 })
    expect(base.getEvents).toHaveBeenCalledWith({ limit: 10 })
    expect(res).toEqual([])
  })
})
