import { describe, expect, it } from 'vitest'
import { cn } from './utils'

describe('cn', () => {
  it('joins class names', () => {
    expect(cn('px-2', 'text-sm')).toBe('px-2 text-sm')
  })

  it('drops falsy values and honours conditional objects', () => {
    const hidden = false
    expect(cn('base', hidden && 'hidden', null, undefined, { active: true, disabled: false })).toBe('base active')
  })

  it('resolves Tailwind conflicts in favour of the later class', () => {
    expect(cn('p-2 p-4')).toBe('p-4')
    expect(cn('bg-primary text-sm', 'bg-destructive')).toBe('text-sm bg-destructive')
  })
})
