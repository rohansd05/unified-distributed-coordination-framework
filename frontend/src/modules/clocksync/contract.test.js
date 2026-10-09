import { describe, expect, it } from 'vitest'
import overviewInitial from '@/test/fixtures/clocksync/overview-initial.json'
import localEvent from '@/test/fixtures/clocksync/local-event.json'
import messageLive from '@/test/fixtures/clocksync/message-live.json'
import error409NodeDown from '@/test/fixtures/clocksync/error-409-node-down.json'
import messageCrashedReceiver from '@/test/fixtures/clocksync/message-crashed-receiver.json'
import traffic from '@/test/fixtures/clocksync/traffic.json'
import berkeleyRound from '@/test/fixtures/clocksync/berkeley-round.json'
import overviewAfterSync from '@/test/fixtures/clocksync/overview-after-sync.json'
import driftUpdated from '@/test/fixtures/clocksync/drift-updated.json'
import verification from '@/test/fixtures/clocksync/verification.json'
import timeline from '@/test/fixtures/clocksync/timeline.json'
import error400Validation from '@/test/fixtures/clocksync/error-400-validation.json'
import error404UnknownNode from '@/test/fixtures/clocksync/error-404-unknown-node.json'
import events from '@/test/fixtures/clocksync/events.json'

describe('clocksync contract fixtures', () => {
  it('overview-initial matches ClockSyncOverviewDto contract', () => {
    expect(overviewInitial.status).toBe('IDLE')
    expect(overviewInitial.actionInProgress).toBeNull()
    expect(overviewInitial.timeDaemonNodeId).toBe(1)
    expect(overviewInitial.nodes).toHaveLength(5)
    expect(overviewInitial.latestRound).toBeNull()

    const node1 = overviewInitial.nodes[0]
    expect(node1.nodeId).toBe(1)
    expect(node1.status).toBe('UP')
    expect(node1.simulatedDrift.simulated).toBe(true)
    expect(node1.simulatedDrift.simulatedReason).toContain('simulated mathematically')

    expect(overviewInitial.limits.maxTrafficSeconds).toBe(30)
    expect(overviewInitial.limits.retainedEventsCapacity).toBe(2000)
    expect(overviewInitial.notes.length).toBeGreaterThan(0)
  })

  it('overview-after-sync includes converged Berkeley round details', () => {
    expect(overviewAfterSync.status).toBe('IDLE')
    expect(overviewAfterSync.latestRound).not.toBeNull()
    const round = overviewAfterSync.latestRound
    expect(round.roundId).toBe(1)
    expect(round.daemonNodeId).toBe(1)
    expect(round.outlierThresholdMillis).toBe(400)
    expect(round.spreadBeforeMillis).toBe(407)
    expect(round.spreadAfterMillis).toBe(0)
    expect(round.adjustments).toHaveLength(5)
    expect(round.simulated).toBe(true)

    // Node 2 adjustment was negative to pull it back down to average
    const node2Adj = round.adjustments.find((a) => a.nodeId === 2)
    expect(node2Adj.beforeOffsetMillis).toBe(242)
    expect(node2Adj.adjustmentMillis).toBe(-218)
    expect(node2Adj.afterOffsetMillis).toBe(26)
  })

  it('local-event matches LocalEventResult contract', () => {
    expect(localEvent.nodeId).toBe(1)
    expect(localEvent.lamportTime).toBeGreaterThan(0)
    expect(localEvent.description).toBeTruthy()
    expect(localEvent.wallTime).toBeTruthy()
  })

  it('message-live and message-crashed-receiver follow UDP honesty contract', () => {
    expect(messageLive.from).toBe(1)
    expect(messageLive.to).toBe(2)
    expect(messageLive.deliveryStatus).toBe('SENT')
    expect(messageLive.deliveryNote).toContain('UDP to port')

    expect(messageCrashedReceiver.from).toBe(1)
    expect(messageCrashedReceiver.to).toBe(3)
    expect(messageCrashedReceiver.deliveryStatus).toBe('UNKNOWN')
    expect(messageCrashedReceiver.deliveryNote).toContain('crashed node 3')
  })

  it('traffic and berkeley-round match asynchronous acceptance contracts', () => {
    expect(traffic.status).toBe('ACCEPTED')
    expect(traffic.sessionId).toBeTruthy()
    expect(traffic.seconds).toBe(2)

    expect(berkeleyRound.status).toBe('ACCEPTED')
    expect(berkeleyRound.roundId).toBe(1)
    expect(berkeleyRound.daemonNodeId).toBe(1)
  })

  it('drift-updated matches NodeDriftResponseDto contract', () => {
    expect(driftUpdated.nodeId).toBe(2)
    expect(driftUpdated.offsetMillis).toBe(95)
    expect(driftUpdated.driftRateMsPerSec).toBe(1.8)
    expect(driftUpdated.simulated).toBe(true)
  })

  it('verification matches CausalVerificationDto contract', () => {
    expect(verification.totalEventsChecked).toBe(20)
    expect(verification.receiveEventsChecked).toBe(9)
    expect(verification.violationsCount).toBe(0)
    expect(verification.passed).toBe(true)
    expect(verification.summary).toContain('PASS')
    expect(verification.violations).toEqual([])
  })

  it('timeline matches TimelineResponseDto contract', () => {
    expect(timeline.limit).toBe(10)
    expect(timeline.events.length).toBe(10)
    const recvEvent = timeline.events.find((e) => e.type === 'RECV')
    expect(recvEvent.causedByTime).toBeGreaterThan(0)
    expect(recvEvent.lamportTime).toBeGreaterThan(recvEvent.causedByTime)
  })

  it('ProblemDetail error fixtures match RFC 7807 contract', () => {
    expect(error409NodeDown.status).toBe(409)
    expect(error409NodeDown.title).toBe('Node down')
    expect(error409NodeDown.nodeId).toBe(3)

    expect(error400Validation.status).toBe(400)
    expect(error400Validation.title).toBe('Invalid request parameters')
    expect(error400Validation.errors.seconds).toBeTruthy()

    expect(error404UnknownNode.status).toBe(404)
    expect(error404UnknownNode.title).toBe('Unknown node')
    expect(error404UnknownNode.nodeId).toBe(99)
  })

  it('events matches published ClusterEvent array format', () => {
    expect(Array.isArray(events)).toBe(true)
    expect(events.length).toBeGreaterThan(0)
    expect(events[0].module).toBe('clocksync')
  })
})
