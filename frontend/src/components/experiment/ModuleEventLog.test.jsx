import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import * as useModuleEventsModule from '@/hooks/useModuleEvents'
import eventsFixture from '@/test/fixtures/multithreading/events.json'
import { causalCompare } from '@/hooks/useEventStream'
import { ModuleEventLog } from './ModuleEventLog'

function mockEvents(value) {
  vi.spyOn(useModuleEventsModule, 'useModuleEvents').mockReturnValue(value)
}

beforeEach(() => {
  mockEvents({ events: [...eventsFixture].sort(causalCompare), loading: false, error: null })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('ModuleEventLog', () => {
  it('lists the module events newest first, with Lamport values in monospace', () => {
    render(<ModuleEventLog moduleId="multithreading" />)

    const log = screen.getByTestId('module-event-log')
    const lamports = [...log.querySelectorAll('li')].map((li) => li.querySelector('.font-mono').textContent)
    const expected = [...eventsFixture].sort(causalCompare).reverse().map((event) => `L:${event.lamportTime}`)
    expect(lamports).toEqual(expected)
    expect(log.textContent).toContain('Batch submitted')
    expect(log.textContent).toContain('Node 1 received 100 requests (0 rejected)')
    expect(log.textContent).toContain(`${eventsFixture.length} events`)
  })

  it('asks for its own module only', () => {
    render(<ModuleEventLog moduleId="multithreading" />)

    expect(useModuleEventsModule.useModuleEvents).toHaveBeenCalledWith(
      expect.objectContaining({ moduleId: 'multithreading', limit: 50 }),
    )
  })

  it('guides the user when there are no events yet', () => {
    mockEvents({ events: [], loading: false, error: null })
    render(<ModuleEventLog moduleId="multithreading" />)

    expect(screen.getByText(/No events for this module yet/)).toBeTruthy()
  })

  it('shows a loading state', () => {
    mockEvents({ events: [], loading: true, error: null })
    render(<ModuleEventLog moduleId="multithreading" />)

    expect(screen.getByText('Loading events…')).toBeTruthy()
  })

  it('shows a failed load as an alert with the reason', () => {
    mockEvents({ events: [], loading: false, error: { detail: 'Backend unreachable' } })
    render(<ModuleEventLog moduleId="multithreading" />)

    expect(screen.getByRole('alert').textContent).toContain('Backend unreachable')
  })
})
