import { describe, expect, it } from 'vitest'
import { EXPERIMENTS, findBySlug, formatStatus, mergeModuleStatus } from './experiments'

/** Phase each experiment arrives in (docs/HANDOFF.md Section 11). */
const ARRIVES_IN = { 1: 10, 2: 3, 3: 4, 4: 5, 5: 6, 6: 8, 7: 9, 8: 7, 9: 11, 10: 12 }

describe('experiment catalog', () => {
  it('has ten entries', () => {
    expect(EXPERIMENTS).toHaveLength(10)
  })

  it('has lab numbers 1..10, unique and sorted', () => {
    expect(EXPERIMENTS.map((e) => e.lab)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
  })

  it('builds every slug as "{lab}-{id}"', () => {
    for (const e of EXPERIMENTS) {
      expect(e.slug).toBe(`${e.lab}-${e.id}`)
    }
    expect(EXPERIMENTS.map((e) => e.slug)).toContain('4-election')
    expect(new Set(EXPERIMENTS.map((e) => e.slug)).size).toBe(10)
  })

  it('findBySlug finds known slugs and returns undefined for unknown ones', () => {
    expect(findBySlug('1-rmi').title).toBe('Client-Server via Java RMI')
    expect(findBySlug('10-matrix').id).toBe('matrix')
    expect(findBySlug('99-nope')).toBeUndefined()
    expect(findBySlug('election')).toBeUndefined()
    expect(findBySlug(undefined)).toBeUndefined()
  })

  it('records the phase each module arrives in', () => {
    for (const e of EXPERIMENTS) {
      expect(e.arrivesIn, `lab ${e.lab}`).toBe(ARRIVES_IN[e.lab])
    }
  })
})

describe('mergeModuleStatus', () => {
  it('assigns backend status to known modules and PLANNED to unbuilt ones', () => {
    const backendModules = [
      { id: 'multithreading', status: 'IDLE' },
      { id: 'election', status: 'RUNNING' },
    ]

    const merged = mergeModuleStatus(EXPERIMENTS, backendModules)
    expect(merged).toHaveLength(10)

    const exp2 = merged.find((e) => e.id === 'multithreading')
    expect(exp2.status).toBe('IDLE')

    const exp4 = merged.find((e) => e.id === 'election')
    expect(exp4.status).toBe('RUNNING')

    const exp1 = merged.find((e) => e.id === 'rmi')
    expect(exp1.status).toBe('PLANNED')
  })

  it('sets status to null for all experiments when modules is null or not an array', () => {
    const mergedNull = mergeModuleStatus(EXPERIMENTS, null)
    expect(mergedNull.every((e) => e.status === null)).toBe(true)

    const mergedUndefined = mergeModuleStatus(EXPERIMENTS, undefined)
    expect(mergedUndefined.every((e) => e.status === null)).toBe(true)
  })
})

describe('formatStatus', () => {
  it('capitalizes the status string', () => {
    expect(formatStatus('PLANNED')).toBe('Planned')
    expect(formatStatus('IDLE')).toBe('Idle')
    expect(formatStatus('RUNNING')).toBe('Running')
    expect(formatStatus(null)).toBe('')
  })
})
