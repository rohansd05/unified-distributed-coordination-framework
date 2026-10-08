import { describe, expect, it } from 'vitest'
import { modulePages } from './registry'
import { MultithreadingPage } from './multithreading/MultithreadingPage'
import { LoadBalancingPage } from './loadbalancing/LoadBalancingPage'

describe('module registry', () => {
  it('registers each module folder by the id its index.jsx exports', () => {
    expect(modulePages.multithreading).toBe(MultithreadingPage)
    expect(modulePages.loadbalancing).toBe(LoadBalancingPage)
  })

  it('has no page for a module that has not been built', () => {
    expect(modulePages.election).toBeUndefined()
  })
})
