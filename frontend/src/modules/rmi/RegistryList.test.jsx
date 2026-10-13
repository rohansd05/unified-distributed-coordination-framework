import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { ApiError } from '@/services/api'
import overview from '@/test/fixtures/rmi/overview-after-start.json'
import registriesAllUp from '@/test/fixtures/rmi/registries-all-up.json'
import registriesCrashed from '@/test/fixtures/rmi/registries-node-3-crashed.json'
import registriesBefore from '@/test/fixtures/rmi/registries-before-start.json'
import { RegistryList } from './RegistryList'

afterEach(cleanup)

describe('RegistryList', () => {
  it('lists each node\'s bound name and stub class', () => {
    render(<RegistryList registries={registriesAllUp} overview={overview} />)
    const node2 = screen.getByTestId('registry-2')
    expect(node2.textContent).toContain('Node 2')
    expect(node2.textContent).toContain('1102')
    expect(node2.textContent).toContain('NodeEndpoint bound to stub jdk.proxy2.$Proxy111')
    expect(screen.queryByTestId('registry-unreachable')).toBeNull()
  })

  it('shows a crashed node as unreachable with the real failure, never as an empty registry', () => {
    render(<RegistryList registries={registriesCrashed} overview={overview} />)
    const node3 = screen.getByTestId('registry-3')
    expect(within(node3).getByTestId('registry-unreachable').textContent).toContain('Unreachable: connection refused.')
    expect(node3.textContent).toContain(overview.failureKinds[0].description)
    expect(node3.textContent).toContain('java.rmi.ConnectException')
    expect(node3.textContent).not.toContain('no name is bound')
    expect(screen.getByTestId('registry-1').textContent).toContain('NodeEndpoint')
  })

  it('shows every registry as unreachable before RMI has started', () => {
    render(<RegistryList registries={registriesBefore} overview={overview} />)
    expect(screen.getAllByTestId('registry-unreachable')).toHaveLength(5)
    expect(screen.getByTestId('registry-1').textContent).toContain('endpoint not started')
  })

  it('shows a loading placeholder before the first read and an error with retry after a failed one', () => {
    const { unmount } = render(<RegistryList registries={null} overview={overview} />)
    expect(document.querySelector('[aria-busy="true"]')).toBeTruthy()
    unmount()
    const onRetry = vi.fn()
    render(<RegistryList registries={null} overview={overview} onRetry={onRetry}
      error={new ApiError({ status: 0, title: 'Backend unreachable', detail: 'Network Error' })} />)
    expect(screen.getByRole('alert').textContent).toContain('Network Error')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
