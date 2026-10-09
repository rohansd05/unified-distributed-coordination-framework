import { describe, expect, it } from 'vitest'
import { inputTypeLabel, jobFallbackLabel, plural, runStateLabel, taskTypeLabel, taskTypePhrase } from './labels'

describe('MapReduce labels', () => {
  it('names run states and task types in plain words, never in capitals', () => {
    expect(['RUNNING', 'COMPLETED', 'FAILED'].map(runStateLabel)).toEqual(['Running', 'Completed', 'Failed'])
    expect(['MAP', 'REDUCE'].map(taskTypeLabel)).toEqual(['Map', 'Reduce'])
    expect(taskTypePhrase('REDUCE')).toBe('reduce')
  })

  it('falls back to sentence case for an unknown value', () => {
    expect(runStateLabel('SOME_NEW_STATE')).toBe('Some new state')
    expect(inputTypeLabel('EVENT_LOG')).toBe('Live cluster event log')
    expect(jobFallbackLabel('avg-latency-per-node')).toBe('Avg latency per node')
  })

  it('pluralises', () => {
    expect(plural(1, 'task')).toBe('1 task')
    expect(plural(2, 'task')).toBe('2 tasks')
  })
})
