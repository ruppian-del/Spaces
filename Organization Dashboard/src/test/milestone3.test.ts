import { describe, expect, it } from 'vitest'
import { subscriptionCapacity, subscriptionMonthlyTotal } from '../billing/pricingCatalog'

describe('Milestone 3 pricing contract', () => {
  it('keeps the approved Foundation plan at $39 with its approved capacity', () => {
    const selection = { peoplePacks: 0, spacePacks: 0, storage100Packs: 0, storage500Packs: 0, moduleIds: [] }
    expect(subscriptionMonthlyTotal(selection)).toBe(39)
    expect(subscriptionCapacity(selection)).toEqual({ people: 250, spaces: 10, storageGB: 10 })
  })

  it('prices capacity and module add-ons without granting unselected capacity', () => {
    const selection = { peoplePacks: 1, spacePacks: 2, storage100Packs: 1, storage500Packs: 1, moduleIds: ['communication', 'agenda', 'content'] }
    expect(subscriptionMonthlyTotal(selection)).toBe(188)
    expect(subscriptionCapacity(selection)).toEqual({ people: 500, spaces: 30, storageGB: 610 })
  })
})
