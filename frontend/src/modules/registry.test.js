import { describe, expect, it } from 'vitest'
import { EXPERIMENTS } from '@/lib/experiments'
import { modulePages } from './registry'
import { MultithreadingPage } from './multithreading/MultithreadingPage'
import { LoadBalancingPage } from './loadbalancing/LoadBalancingPage'

describe('module registry', () => {
  it('registers each module folder by the id its index.jsx exports', () => {
    expect(modulePages.multithreading).toBe(MultithreadingPage)
    expect(modulePages.loadbalancing).toBe(LoadBalancingPage)
  })

  it('registers a page for every catalog experiment', () => {
    expect(Object.keys(modulePages).sort()).toEqual(EXPERIMENTS.map((experiment) => experiment.id).sort())
  })
})
