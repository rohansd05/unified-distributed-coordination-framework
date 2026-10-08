import { describe, expect, it } from 'vitest'
import { capacityLabel, formatMillis, moduleStatusLabel, requestStatusLabel, workloadLabel } from './labels'

describe('labels', () => {
  it('names every workload, request status, capacity and module status in sentence case', () => {
    expect(['CPU_HASH', 'IO_SIMULATED', 'MIXED'].map(workloadLabel)).toEqual(['Hashing (CPU)', 'Waiting (IO)', 'Mixed'])
    expect(['QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REJECTED'].map(requestStatusLabel))
      .toEqual(['Queued', 'Running', 'Completed', 'Failed', 'Rejected'])
    expect(['FAST', 'MEDIUM', 'SLOW'].map(capacityLabel)).toEqual(['Fast', 'Medium', 'Slow'])
    expect(['IDLE', 'RUNNING', 'BUSY', 'ERROR'].map(moduleStatusLabel)).toEqual(['Idle', 'Running', 'Busy', 'Error'])
  })

  it('turns an unknown value into sentence case, never ALL-CAPS', () => {
    expect(workloadLabel('NEW_KIND_OF_WORK')).toBe('New kind of work')
    expect(requestStatusLabel(undefined)).toBe('')
  })

  it('formats milliseconds, and gives null for anything that is not a finite number', () => {
    expect(formatMillis(0)).toBe('0.0')
    expect(formatMillis(3.456)).toBe('3.5')
    expect(formatMillis(231.9)).toBe('232')
    expect(formatMillis(Number.NaN)).toBeNull()
    expect(formatMillis(null)).toBeNull()
    expect(formatMillis('12')).toBeNull()
  })
})
