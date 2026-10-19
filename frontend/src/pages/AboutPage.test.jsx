import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ClusterContext } from '@/services/cluster/ClusterContext'
import { EXPERIMENTS } from '@/lib/experiments'
import { AboutPage } from './AboutPage'

afterEach(cleanup)

function renderAbout(cluster) {
  return render(
    <ClusterContext.Provider value={{ cluster, info: null, modules: [], loading: false, error: null, wakingSince: null, refresh: () => {} }}>
      <MemoryRouter><AboutPage /></MemoryRouter>
    </ClusterContext.Provider>,
  )
}

describe('AboutPage', () => {
  it('links all ten experiments and names the served cluster size', () => {
    renderAbout({ size: 3, upCount: 3, nodes: [] })
    for (const experiment of EXPERIMENTS) {
      expect(screen.getByRole('link', { name: `Experiment ${experiment.lab}, ${experiment.title}` }).getAttribute('href'))
        .toBe(`/experiments/${experiment.slug}`)
    }
    expect(document.body.textContent).toContain('a cluster of 3 nodes')
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent))
      .toEqual(['How it is built', 'The ten experiments', 'What is real and what is simulated'])
  })

  it('has no placeholder wording, and no number before the cluster answers', () => {
    renderAbout(null)
    expect(document.body.textContent).not.toMatch(/phase|planned|coming soon|five nodes/i)
    expect(document.body.textContent).toContain('a cluster of nodes')
  })
})
