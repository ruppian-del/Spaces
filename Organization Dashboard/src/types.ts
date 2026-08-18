export type OrganizationStatus = 'active' | 'suspended'
export type OrganizationRole = 'primary_admin' | 'admin' | 'member'

export interface Organization {
  id: string
  name: string
  description: string | null
  contactEmail: string | null
  website: string | null
  logoDataUrl: string | null
  status: OrganizationStatus
  entitlements: {
    peopleCapacity: number | null
    activeSpaceCapacity: number | null
    enabledModuleIds: string[]
    mediaStorageCapacityBytes: number | null
  }
  usage: { peopleCount: number; activeSpaceCount: number; mediaStorageBytes: number }
}

export interface OrganizationIdentityInput {
  name: string
  description: string
  contactEmail: string
  website: string
  logoDataUrl: string
}

export interface OrganizationAdministrator {
  id: string
  userId: string
  displayName: string
  email: string | null
  role: OrganizationRole
  status: 'active' | 'suspended'
}

export type OrganizationMember = OrganizationAdministrator

export interface OrganizationInvitation {
  id: string
  organizationId: string
  organizationName: string
  role: 'admin' | 'member'
  active: boolean
  createdBy: string
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

export interface SpaceDashboardSpace {
  id: string
  organizationId: string | null
  name: string
  emoji: string
  description: string
  memberCount: number
  enabledModuleIds: string[]
  moduleOrder: string[]
  tintHex: string
  template: string
  allowedModuleIds: string[]
  role: 'organization_admin' | 'owner' | 'admin'
}

export type SpaceMemberRole = 'owner' | 'admin' | 'moderator' | 'member' | 'guest'

export interface SpaceDashboardMember {
  id: string
  displayName: string
  emojiAvatar: string
  role: SpaceMemberRole
}

export interface SpaceDashboardInvite {
  code: string
  expiresAt: Date
}


export interface SpaceDashboardActivity {
  id: string
  title: string
  actorName: string
  createdAt: Date | null
}

export type LoadState =
  | { kind: 'loading' }
  | { kind: 'empty' }
  | { kind: 'permission-denied' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; data: DashboardData }
