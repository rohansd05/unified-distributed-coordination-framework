import { describe, expect, it } from 'vitest'
import {
  STRATEGY_ORDER,
  actionStateLabel,
  capacityLabel,
  formatMillis,
  nodeStatusLabel,
  strategyLabel,
  strategyPhrase,
  threadsText,
} from './labels'

describe('load balancing labels', () => {
  it('names every strategy in plain sentence case, in the comparison order', () => {
    expect(STRATEGY_ORDER.map(strategyLabel)).toEqual([
      'Round robin', 'Weighted round robin', 'Least connections', 'Least response time',
    ])
    expect(strategyPhrase('LEAST_RESPONSE_TIME')).toBe('least response time')
  })

  it('names action states and node states, and falls back to sentence case for unknown values', () => {
    expect(['RUNNING', 'FINISHED', 'FAILED'].map(actionStateLabel)).toEqual(['Running', 'Finished', 'Failed'])
    expect(nodeStatusLabel('CRASHED')).toBe('Crashed')
    expect(strategyLabel('RANDOM_CHOICE')).toBe('Random choice')
    expect(strategyLabel(null)).toBe('')
  })

  it('reuses Experiment 2 capacity names and millisecond formatting', () => {
    expect(capacityLabel('SLOW')).toBe('Slow')
    expect(formatMillis(622.55)).toBe('623')
    expect(formatMillis(8.04)).toBe('8.0')
    expect(formatMillis(null)).toBeNull()
    expect(threadsText(1)).toBe('1 thread')
    expect(threadsText(4)).toBe('4 threads')
  })
})
