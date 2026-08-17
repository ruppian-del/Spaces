import type { DashboardData } from '../types'

export const dashboardFixture: DashboardData = {
  organization: {
    id: 'org-test', name: 'Northstar Community', status: 'active',
    entitlements: { peopleCapacity: 250, activeSpaceCapacity: 10, enabledModuleIds: ['general', 'events'], mediaStorageCapacityBytes: 10 * 1024 ** 3 },
    usage: { peopleCount: 184, activeSpaceCount: 1, mediaStorageBytes: 4 * 1024 ** 3 },
  },
  administrators: [{ id: 'a1', userId: 'a1', displayName: 'Alex Morgan', email: 'alex@example.org', role: 'primary_admin', status: 'active' }],
  spaces: [
    { id: 's1', name: 'Everyone', emoji: '🏠', memberIds: ['member-1', 'member-2'], memberCount: 2, isArchived: false },
    { id: 's2', name: 'Summer Gathering 2025', emoji: '☀️', memberIds: ['member-2', 'member-3'], memberCount: 2, isArchived: true },
  ],
}
