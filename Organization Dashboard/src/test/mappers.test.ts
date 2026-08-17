import { describe, expect, it } from 'vitest'
import { mapAdministrator, mapOrganization, mapSpace, mergeAdministratorProfile } from '../data/mappers'

describe('mobile-compatible organization mapping', () => {
  it('maps nested entitlements and usage without changing field names', () => {
    const result = mapOrganization('org-1', {
      name: 'Northstar', status: 'suspended',
      entitlements: { peopleCapacity: 350, activeSpaceCapacity: 14, enabledModuleIds: ['general', 'rooms'], mediaStorageCapacityBytes: 10737418240 },
      usage: { peopleCount: 122, activeSpaceCount: 8, mediaStorageBytes: 1024 },
    })
    expect(result).toMatchObject({ id: 'org-1', status: 'suspended', entitlements: { enabledModuleIds: ['general', 'rooms'] }, usage: { peopleCount: 122 } })
  })

  it('uses safe read defaults for missing optional data', () => {
    expect(mapOrganization('org-2', { name: 'Community' })).toMatchObject({
      status: 'active',
      entitlements: {
        peopleCapacity: 250,
        activeSpaceCapacity: 10,
        enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
        mediaStorageCapacityBytes: 10737418240,
      },
      usage: { peopleCount: 0, activeSpaceCount: 0, mediaStorageBytes: 0 },
    })
    expect(mapOrganization('bad', {})).toBeNull()
  })

  it('preserves a partially configured entitlement block instead of mixing in Foundation values', () => {
    expect(mapOrganization('org-3', { name: 'Configured', entitlements: { peopleCapacity: 500 } })?.entitlements).toEqual({
      peopleCapacity: 500,
      activeSpaceCapacity: null,
      enabledModuleIds: [],
      mediaStorageCapacityBytes: null,
    })
  })

  it('matches iOS effective entitlements by preferring organization debug overrides', () => {
    expect(mapOrganization('org-overridden', {
      name: 'ArcInteractive',
      entitlements: {
        peopleCapacity: 250,
        activeSpaceCapacity: 10,
        enabledModuleIds: ['general', 'events', 'polls', 'members', 'settings'],
        mediaStorageCapacityBytes: 10 * 1024 ** 3,
      },
      debugEntitlementOverrides: {
        peopleCapacity: 3,
        activeSpaceCapacity: 1,
        enabledModuleIds: ['general', 'announcements', 'photos', 'events', 'members'],
        mediaStorageCapacityBytes: 1000 * 1024 ** 2,
      },
    })?.entitlements).toEqual({
      peopleCapacity: 3,
      activeSpaceCapacity: 1,
      enabledModuleIds: ['general', 'announcements', 'photos', 'events', 'members'],
      mediaStorageCapacityBytes: 1000 * 1024 ** 2,
    })
  })

  it('treats zero-valued capacities with no modules as an unconfigured Foundation organization', () => {
    expect(mapOrganization('org-4', {
      name: 'Founding Organization',
      entitlements: {
        peopleCapacity: 0,
        activeSpaceCapacity: 0,
        enabledModuleIds: [],
        mediaStorageCapacityBytes: 0,
      },
    })?.entitlements).toEqual({
      peopleCapacity: 250,
      activeSpaceCapacity: 10,
      enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
      mediaStorageCapacityBytes: 10737418240,
    })
  })

  it('maps administrator roles and organization-owned Space metadata', () => {
    expect(mapAdministrator('u1', { userId: 'u1', displayName: 'Ian', role: 'primary_admin' })?.role).toBe('primary_admin')
    expect(mapSpace('s1', { name: 'Everyone', emoji: '🏠', memberIds: ['a', 'b'], isArchived: true })).toEqual({ id: 's1', name: 'Everyone', emoji: '🏠', memberIds: ['a', 'b'], memberCount: 2, isArchived: true })
  })

  it('prefers the canonical user profile name over a phone-number member snapshot', () => {
    const administrator = mapAdministrator('u1', { userId: 'u1', displayName: '+1 (555) 867-5309', role: 'primary_admin' })!
    expect(administrator.displayName).toBe('Administrator')
    expect(mergeAdministratorProfile(administrator, { displayName: 'Ian Rupp', email: 'ian@example.com' })).toMatchObject({
      displayName: 'Ian Rupp',
      email: 'ian@example.com',
    })
  })
})
