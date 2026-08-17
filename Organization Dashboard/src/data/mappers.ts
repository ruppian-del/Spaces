import type { DocumentData, QueryDocumentSnapshot } from 'firebase/firestore'
import type { Organization, OrganizationAdministrator, OrganizationRole, OrganizationSpace } from '../types'

const numberOrNull = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null

const positiveNumberOrNull = (value: unknown): number | null => {
  const number = numberOrNull(value)
  return number !== null && number > 0 ? number : null
}

const nonNegative = (value: unknown): number => {
  const number = numberOrNull(value)
  return number === null ? 0 : Math.max(0, number)
}

const looksLikePhoneNumber = (value: string): boolean =>
  /^[+\d\s().-]+$/.test(value) && value.replace(/\D/g, '').length >= 7

const usableName = (value: unknown): string | null => {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed && !looksLikePhoneNumber(trimmed) ? trimmed : null
}

const FOUNDATION_ENTITLEMENTS = {
  peopleCapacity: 250,
  activeSpaceCapacity: 10,
  enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
  mediaStorageCapacityBytes: 10 * 1024 ** 3,
} as const

export function mapOrganization(id: string, data: DocumentData): Organization | null {
  if (typeof data.name !== 'string' || !data.name.trim()) return null
  // Match the iOS OrganizationService read contract: organization-wide debug
  // overrides take precedence over the stored package when they are present.
  const debugOverrides = data.debugEntitlementOverrides && typeof data.debugEntitlementOverrides === 'object'
    ? data.debugEntitlementOverrides
    : null
  const entitlements = debugOverrides
    ?? (data.entitlements && typeof data.entitlements === 'object' ? data.entitlements : {})
  const usage = data.usage && typeof data.usage === 'object' ? data.usage : {}
  const capacityValue = debugOverrides ? numberOrNull : positiveNumberOrNull
  const storedPeopleCapacity = capacityValue(entitlements.peopleCapacity)
  const storedActiveSpaceCapacity = capacityValue(entitlements.activeSpaceCapacity)
  const storedModuleIds = Array.isArray(entitlements.enabledModuleIds)
    ? entitlements.enabledModuleIds.filter((value: unknown): value is string => typeof value === 'string')
    : []
  const storedStorageCapacity = capacityValue(entitlements.mediaStorageCapacityBytes)
  const isCompletelyUnconfigured = storedPeopleCapacity === null
    && storedActiveSpaceCapacity === null
    && storedModuleIds.length === 0
    && storedStorageCapacity === null
  return {
    id,
    name: data.name,
    description: typeof data.description === 'string' && data.description.trim() ? data.description.trim() : null,
    contactEmail: typeof data.contactEmail === 'string' && data.contactEmail.trim() ? data.contactEmail.trim() : null,
    website: typeof data.website === 'string' && data.website.trim() ? data.website.trim() : null,
    logoDataUrl: typeof data.logoDataUrl === 'string' && data.logoDataUrl.trim() ? data.logoDataUrl.trim() : null,
    status: data.status === 'suspended' ? 'suspended' : 'active',
    entitlements: {
      peopleCapacity: isCompletelyUnconfigured ? FOUNDATION_ENTITLEMENTS.peopleCapacity : storedPeopleCapacity,
      activeSpaceCapacity: isCompletelyUnconfigured ? FOUNDATION_ENTITLEMENTS.activeSpaceCapacity : storedActiveSpaceCapacity,
      enabledModuleIds: isCompletelyUnconfigured ? [...FOUNDATION_ENTITLEMENTS.enabledModuleIds] : storedModuleIds,
      mediaStorageCapacityBytes: isCompletelyUnconfigured ? FOUNDATION_ENTITLEMENTS.mediaStorageCapacityBytes : storedStorageCapacity,
    },
    usage: {
      peopleCount: nonNegative(usage.peopleCount),
      activeSpaceCount: nonNegative(usage.activeSpaceCount),
      mediaStorageBytes: nonNegative(usage.mediaStorageBytes),
    },
  }
}

export function mapAdministrator(id: string, data: DocumentData): OrganizationAdministrator | null {
  const roles: OrganizationRole[] = ['primary_admin', 'admin', 'member']
  if (typeof data.userId !== 'string' || !roles.includes(data.role)) return null
  return {
    id,
    userId: data.userId,
    displayName: usableName(data.displayName) ?? 'Administrator',
    email: typeof data.email === 'string' ? data.email : null,
    role: data.role,
    status: data.status === 'suspended' ? 'suspended' : 'active',
  }
}

export function mergeAdministratorProfile(
  administrator: OrganizationAdministrator,
  profile: DocumentData | null,
): OrganizationAdministrator {
  if (!profile) return administrator
  return {
    ...administrator,
    displayName: usableName(profile.displayName) ?? administrator.displayName,
    email: typeof profile.email === 'string' && profile.email.trim() ? profile.email : administrator.email,
  }
}

export function mergeAdministratorAuthDisplayName(
  administrator: OrganizationAdministrator,
  currentUser: { uid: string; displayName: string | null; email: string | null },
): OrganizationAdministrator {
  if (administrator.userId !== currentUser.uid) return administrator
  return {
    ...administrator,
    displayName: usableName(currentUser.displayName) ?? administrator.displayName,
    email: currentUser.email ?? administrator.email,
  }
}

export function mapSpace(id: string, data: DocumentData): OrganizationSpace {
  const memberIds = Array.isArray(data.memberIds)
    ? data.memberIds.filter((value: unknown): value is string => typeof value === 'string')
    : []
  return {
    id,
    name: typeof data.name === 'string' && data.name.trim() ? data.name : 'Untitled Space',
    emoji: typeof data.emoji === 'string' && data.emoji ? data.emoji : '🏠',
    memberIds,
    memberCount: memberIds.length,
    isArchived: data.isArchived === true,
  }
}

export const mapOrganizationDocument = (doc: QueryDocumentSnapshot<DocumentData>) => mapOrganization(doc.id, doc.data())
