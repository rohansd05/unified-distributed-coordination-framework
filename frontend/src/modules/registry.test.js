import { describe, expect, it } from 'vitest'
import { EXPERIMENTS } from '@/lib/experiments'
import { modulePages, pageLoaders } from './registry'
import { MultithreadingPage } from './multithreading/MultithreadingPage'
import { LoadBalancingPage } from './loadbalancing/LoadBalancingPage'

describe('module registry', () => {
  it('loads each module folder\'s page on demand', async () => {
    expect((await pageLoaders.multithreading()).Page).toBe(MultithreadingPage)
    expect((await pageLoaders.loadbalancing()).Page).toBe(LoadBalancingPage)
  })

  it('names every folder after the id its index.jsx exports', async () => {
    for (const [id, load] of Object.entries(pageLoaders)) {
      expect((await load()).id).toBe(id)
    }
  })

  it('registers a lazy page for every catalog experiment', () => {
    const ids = EXPERIMENTS.map((experiment) => experiment.id).sort()
    expect(Object.keys(modulePages).sort()).toEqual(ids)
    expect(Object.keys(pageLoaders).sort()).toEqual(ids)
    for (const page of Object.values(modulePages)) {
      expect(page.$$typeof).toBe(Symbol.for('react.lazy'))
    }
  })
})
