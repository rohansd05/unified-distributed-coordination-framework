import { describe, expect, it } from 'vitest'
import { EXPERIMENTS, findBySlug } from './experiments'

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
