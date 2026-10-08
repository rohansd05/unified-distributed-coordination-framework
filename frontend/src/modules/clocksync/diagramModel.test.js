import { describe, expect, it } from 'vitest'
import {
  MAX_DISPLAYED_EVENTS,
  buildDiagramModel,
  pairMessages,
  sortEventsTotalOrder,
} from './diagramModel'

describe('diagramModel', () => {
  const nodes = [
    { nodeId: 1, status: 'UP' },
    { nodeId: 2, status: 'UP' },
    { nodeId: 3, status: 'UP' },
  ]

  it('sorts events in Lamport total order (lamportTime ascending, nodeId ascending)', () => {
    const raw = [
      { nodeId: 2, lamportTime: 5, description: 'B' },
      { nodeId: 1, lamportTime: 5, description: 'A' },
      { nodeId: 3, lamportTime: 2, description: 'C' },
    ]
    const sorted = sortEventsTotalOrder(raw)
    expect(sorted).toEqual([
      { nodeId: 3, lamportTime: 2, description: 'C' },
      { nodeId: 1, lamportTime: 5, description: 'A' },
      { nodeId: 2, lamportTime: 5, description: 'B' },
    ])
  })

  it('pairs SEND and RECV events by messageId', () => {
    const events = [
      { nodeId: 1, type: 'SEND', messageId: 101, lamportTime: 2 },
      { nodeId: 2, type: 'RECV', messageId: 101, lamportTime: 3 },
      { nodeId: 1, type: 'LOCAL', messageId: null, lamportTime: 4 },
    ]
    const pairs = pairMessages(events)
    expect(pairs).toHaveLength(1)
    expect(pairs[0].messageId).toBe(101)
    expect(pairs[0].sendEvent.nodeId).toBe(1)
    expect(pairs[0].recvEvent.nodeId).toBe(2)
  })

  it('a Receive whose Send was dropped is drawn without an arrow and is never a violation', () => {
    // Send 99 is not in the events list (e.g. dropped because it was too old)
    const events = [
      { nodeId: 2, type: 'RECV', messageId: 99, lamportTime: 4 },
    ]
    const violations = [
      { nodeId: 2, actualLamportTime: 4, expectedRelationTime: 5, peerId: 1, message: 'Receive time 4 <= send time 5' },
    ]

    const model = buildDiagramModel({ nodes, events, violations })
    expect(model.arrows).toHaveLength(0)
    expect(model.events[0].isViolation).toBe(false)
  })

  it('bounds rendering to MAX_DISPLAYED_EVENTS (40)', () => {
    const manyEvents = Array.from({ length: 50 }, (_, i) => ({
      nodeId: (i % 3) + 1,
      type: 'LOCAL',
      lamportTime: i + 1,
      messageId: null,
    }))

    const model = buildDiagramModel({ nodes, events: manyEvents })
    expect(model.displayedCount).toBe(MAX_DISPLAYED_EVENTS)
    expect(model.events[0].lamportTime).toBe(11) // 50 - 40 + 1 = 11
    expect(model.events[model.events.length - 1].lamportTime).toBe(50)
  })

  it('identifies causal violation when send is present and backend reports violation', () => {
    const events = [
      { nodeId: 1, type: 'SEND', messageId: 200, lamportTime: 5 },
      { nodeId: 2, type: 'RECV', messageId: 200, lamportTime: 4 }, // violation: recv <= send
    ]
    const violations = [
      { nodeId: 2, actualLamportTime: 4, expectedRelationTime: 5, peerId: 1, message: 'Causal invariant violated: recv 4 <= send 5' },
    ]

    const model = buildDiagramModel({ nodes, events, violations })
    expect(model.arrows).toHaveLength(1)
    expect(model.events[1].isViolation).toBe(true)
    expect(model.events[1].violationDetail).toContain('recv 4 <= send 5')
    expect(model.arrows[0].isViolation).toBe(true)
  })
})
