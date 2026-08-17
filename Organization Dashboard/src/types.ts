export type OrganizationStatus = 'active' | 'suspended'
export type OrganizationRole = 'primary_admin' | 'admin' | 'member'

export interface Organization {
  id: string
  name: string
  status: OrganizationStatus
  entitlements: {
    peopleCapacity: number | null
    activeSpaceCapacity: number | null
    enabledModuleIds: string[]
    mediaStorageCapacityBytes: number | null
  }
  usage: { peopleCount: number; activeSpaceCount: number; mediaStorageBytes: number }
}

export interface OrganizationAdministrator {
  id: string
  userId: string
  displayName: string
  email: string | null
  role: OrganizationRole
  status: 'active' | 'suspended'
}

export interface OrganizationSpace {
  id: string
  name: string
  emoji: string
  memberIds: string[]
  memberCount: number
  isArchived: boolean
}

export interface DashboardData {
  organization: Organization
  administrators: OrganizationAdministrator[]
  spaces: OrganizationSpace[]
}

export type LoadState =
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'permission-denied' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; data: DashboardData }
