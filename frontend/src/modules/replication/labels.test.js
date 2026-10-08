import { describe, expect, it } from 'vitest'
import {
  CELL_STATES,
  applyResultLabel,
  cellStateLabel,
  cellStateMeaning,
  itemsText,
  modelLabel,
  nodeStatusText,
  nodesText,
  problemText,
  pushStatusLabel,
  roleLabel,
  valueText,
} from './labels'

const ALL_CAPS = /\b[A-Z]{2,}\b/

describe('replication labels', () => {
  it('names every replica state the backend sends, in plain words, with a distinct meaning', () => {
    expect(CELL_STATES).toEqual(['CURRENT', 'STALE', 'MISSING', 'AHEAD', 'CONFLICT', 'UNREACHABLE', 'ABSENT'])
    const labels = CELL_STATES.map(cellStateLabel)
    expect(labels).toEqual(['Current', 'Stale', 'Missing', 'Ahead', 'Conflict', 'Unreachable', 'Not held'])
    expect(new Set(CELL_STATES.map(cellStateMeaning)).size).toBe(CELL_STATES.length)
    for (const text of [...labels, ...CELL_STATES.map(cellStateMeaning)]) {
      expect(text).not.toMatch(ALL_CAPS)
    }
  })

  it('a null state (no readable primary) is "Not compared"', () => {
    expect(cellStateLabel(null)).toBe('Not compared')
    expect(cellStateMeaning(null)).toContain('no readable primary')
  })

  it('labels models, push statuses, apply results and roles; unknown values fall back to sentence case', () => {
    expect(modelLabel('ASYNCHRONOUS')).toBe('Asynchronous')
    expect(pushStatusLabel('ACKED')).toBe('Acknowledged')
    expect(pushStatusLabel('NOT_SENT')).toBe('Not sent')
    expect(applyResultLabel('STALE')).toBe('Rejected as stale')
    expect(applyResultLabel('STALE_EPOCH')).toBe('Refused: older epoch')
    expect(roleLabel('PRIMARY')).toBe('Primary')
    expect(roleLabel(null)).toBe('No role')
    expect(cellStateLabel('SOME_NEW_STATE')).toBe('Some new state')
  })

  it('reads node status through the shared helper: the backend sends UP or CRASHED', () => {
    expect(nodeStatusText('UP')).toBe('Up')
    expect(nodeStatusText('CRASHED')).toBe('Crashed')
  })

  it('formats values, node lists, item counts and ProblemDetails', () => {
    expect(valueText('')).toBe('(empty)')
    expect(valueText('eu;west~1')).toBe('eu;west~1')
    expect(nodesText([])).toBe('no node')
    expect(nodesText([3])).toBe('node 3')
    expect(nodesText([2, 3, 4])).toBe('nodes 2, 3 and 4')
    expect(itemsText(1)).toBe('1 item')
    expect(itemsText(0)).toBe('0 items')
    expect(problemText({ title: 'Node down', detail: 'Node 4 is crashed' })).toBe('Node down: Node 4 is crashed')
    expect(problemText({ title: 'Error', detail: 'Error' })).toBe('Error')
    expect(problemText(new Error('boom'))).toBe('boom')
    expect(problemText(null)).toBe('')
  })
})
