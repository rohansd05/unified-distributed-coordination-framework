import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import overviewAfterBully from '@/test/fixtures/election/overview-after-bully.json'
import overviewLeaderCrashed from '@/test/fixtures/election/overview-leader-crashed.json'
import overviewAfterReelection from '@/test/fixtures/election/overview-after-reelection.json'
import overviewInitial from '@/test/fixtures/election/overview-initial.json'
import events from '@/test/fixtures/election/events.json'
import { ElectionRing } from './ElectionRing'
import { roundMessages } from './electionModel'

afterEach(cleanup)

const nodeGroup = (id) => screen.getByTestId(`ring-node-${id}`)

describe('ElectionRing', () => {
  it('marks the leader passed in (from cluster roles) with the word "Leader", not the overview\'s own coordinator', () => {
    render(<ElectionRing nodes={overviewAfterBully.nodes} leaderId={3} messages={[]} />)
    expect(within(nodeGroup(3)).getByText('Leader')).toBeTruthy()
    expect(within(nodeGroup(5)).queryByText('Leader')).toBeNull()
    expect(screen.getByRole('img').textContent).toContain('node 3 is the leader')
  })

  it('names a crashed node in words, not by colour alone', () => {
    render(<ElectionRing nodes={overviewLeaderCrashed.nodes} leaderId={null} messages={[]} />)
    expect(within(nodeGroup(5)).getByText('Crashed')).toBeTruthy()
    expect(screen.getByTestId('ring-text').textContent).toContain('Node 5: crashed')
    expect(screen.getByRole('img').textContent).toContain('no leader')
  })

  it('shows suspicions and who each node follows, as text', () => {
    render(<ElectionRing nodes={overviewAfterReelection.nodes} leaderId={4} messages={[]} />)
    expect(within(nodeGroup(5)).getByText('Suspected')).toBeTruthy()
    const text = screen.getByTestId('ring-text').textContent
    expect(text).toContain('Node 1: up; follows node 4; suspects node 5')
    expect(text).toContain('Node 4: up, the leader')
  })

  it('says a node\'s election service has not started yet (detector idle)', () => {
    render(<ElectionRing nodes={overviewInitial.nodes} leaderId={null} messages={[]} />)
    expect(screen.getByTestId('ring-text').textContent).toContain('Node 1: up; election service not started.')
  })

  it('draws the round\'s messages as arrows labelled with their type and the event\'s own Lamport value', () => {
    const lastFinished = events.filter((e) => e.type === 'ELECTION_ROUND_FINISHED').at(-1).data
    const messages = roundMessages(events, lastFinished)
    render(<ElectionRing nodes={overviewAfterBully.nodes} leaderId={5} messages={messages} />)
    const arrows = screen.getAllByTestId('message-arrow')
    expect(arrows).toHaveLength(messages.length)
    expect(arrows[0].textContent).toContain(`L:${messages[0].lamportTime}`)
    expect(arrows[0].getAttribute('class')).toContain('motion-safe:animate-in')
    expect(arrows[0].getAttribute('class')).not.toMatch(/(^|\s)animate-in/)
  })

  it('shows a real empty state before the overview arrives', () => {
    render(<ElectionRing nodes={null} leaderId={null} messages={[]} />)
    expect(screen.getByTestId('election-ring').textContent).toContain('appears as soon as the election module answers')
  })
})
