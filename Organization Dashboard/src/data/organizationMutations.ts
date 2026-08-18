import {
  collection, doc, getDoc, getDocs, query, serverTimestamp, updateDoc, where, writeBatch,
  type DocumentData, type QueryDocumentSnapshot,
} from 'firebase/firestore'
import type { User } from 'firebase/auth'
import { db } from './firebase'
import { mapAdministrator, mapOrganization } from './mappers'
import { cleanOrganizationIdentity, FOUNDATION_ENTITLEMENTS } from './organizationValidation'
import type { Organization, OrganizationIdentityInput, OrganizationInvitation, OrganizationMember, OrganizationRole } from '../types'

const inFlightCreations = new Map<string, Promise<Organization>>()
const FREE_ORGANIZATION_ENTITLEMENTS = {
  peopleCapacity: 25,
  activeSpaceCapacity: 1,
  enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
  mediaStorageCapacityBytes: 1024 ** 3,
} as const

export function deduplicateInFlight<T>(operations: Map<string, Promise<T>>, key: string, operation: () => Promise<T>): Promise<T> {
  const existing = operations.get(key)
  if (existing) return existing
  const pending = operation().finally(() => operations.delete(key))
  operations.set(key, pending)
  return pending
}

function identityFields(input: OrganizationIdentityInput) {
  const clean = cleanOrganizationIdentity(input)
  return {
    name: clean.name,
    description: clean.description || null,
    contactEmail: clean.contactEmail || null,
    website: clean.website || null,
    logoDataUrl: clean.logoDataUrl || null,
  }
}

export function buildFoundingOrganizationData(input: OrganizationIdentityInput, creatorId: string, requestId: string, timestamp: unknown, startWithFoundation = true) {
  const entitlements = startWithFoundation ? FOUNDATION_ENTITLEMENTS : FREE_ORGANIZATION_ENTITLEMENTS
  return {
    ...identityFields(input),
    status: 'active',
    memberIds: [creatorId],
    createdBy: creatorId,
    creationRequestId: requestId,
    createdAt: timestamp,
    entitlements: {
      peopleCapacity: entitlements.peopleCapacity,
      activeSpaceCapacity: entitlements.activeSpaceCapacity,
      enabledModuleIds: [...entitlements.enabledModuleIds].sort(),
      mediaStorageCapacityBytes: entitlements.mediaStorageCapacityBytes,
    },
    usage: { peopleCount: 1, activeSpaceCount: 0, mediaStorageBytes: 0 },
  }
}

export function buildPrimaryAdministratorData(creator: Pick<User, 'uid' | 'displayName' | 'email'>, timestamp: unknown) {
  const creatorName = creator.displayName?.trim() || creator.email?.split('@')[0] || 'Administrator'
  return { userId: creator.uid, displayName: creatorName, email: creator.email ?? null, role: 'primary_admin', status: 'active', joinedAt: timestamp }
}

export function canManageOrganizationSettings(member: Pick<OrganizationMember, 'role'> | undefined): boolean {
  return member?.role === 'primary_admin'
}

export function newOrganizationRequestId(): string {
  return crypto.randomUUID().replaceAll('-', '')
}

async function createFoundationOrganization(
  input: OrganizationIdentityInput,
  creator: User,
  requestId: string,
  startWithFoundation: boolean,
): Promise<Organization> {
  if (!db) throw new Error('Firebase is not configured.')
  const organizationRef = doc(db, 'organizations', requestId)
  const existing = await getDoc(organizationRef)
  if (existing.exists()) {
    const organization = mapOrganization(existing.id, existing.data())
    if (organization && existing.data().creationRequestId === requestId && existing.data().createdBy === creator.uid) return organization
    throw new Error('This setup request conflicts with an existing organization.')
  }
  const timestamp = serverTimestamp()
  const batch = writeBatch(db)
  batch.set(organizationRef, buildFoundingOrganizationData(input, creator.uid, requestId, timestamp, startWithFoundation))
  batch.set(doc(organizationRef, 'members', creator.uid), buildPrimaryAdministratorData(creator, timestamp))
  await batch.commit()
  const created = await getDoc(organizationRef)
  const organization = created.exists() ? mapOrganization(created.id, created.data()) : null
  if (!organization) throw new Error('Organization creation completed, but the organization could not be reloaded.')
  return organization
}

export function createFoundationOrganizationOnce(input: OrganizationIdentityInput, creator: User, requestId: string, startWithFoundation = true): Promise<Organization> {
  return deduplicateInFlight(inFlightCreations, requestId, () => createFoundationOrganization(input, creator, requestId, startWithFoundation))
}

export async function updateOrganizationIdentity(organizationId: string, input: OrganizationIdentityInput): Promise<void> {
  if (!db) throw new Error('Firebase is not configured.')
  await updateDoc(doc(db, 'organizations', organizationId), { ...identityFields(input), updatedAt: serverTimestamp() })
}

function invitationCode(): string {
  return crypto.randomUUID().replaceAll('-', '').slice(0, 8).toUpperCase()
}

export async function createAdministratorInvitation(organization: Organization, creatorId: string): Promise<OrganizationInvitation> {
  if (!db) throw new Error('Firebase is not configured.')
  const id = invitationCode()
  const invitation: OrganizationInvitation = { id, organizationId: organization.id, organizationName: organization.name, role: 'admin', active: true, createdBy: creatorId }
  await writeBatch(db).set(doc(db, 'organizationInvites', id), { ...invitation, createdAt: serverTimestamp() }).commit()
  return invitation
}

function mapInvitation(snapshot: QueryDocumentSnapshot<DocumentData>): OrganizationInvitation | null {
  const value = snapshot.data()
  if (typeof value.organizationId !== 'string' || typeof value.organizationName !== 'string' || !['admin', 'member'].includes(value.role)) return null
  return { id: snapshot.id, organizationId: value.organizationId, organizationName: value.organizationName, role: value.role, active: value.active === true, createdBy: typeof value.createdBy === 'string' ? value.createdBy : '' }
}

export async function listPendingInvitations(organizationId: string): Promise<OrganizationInvitation[]> {
  if (!db) return []
  const snapshot = await getDocs(query(collection(db, 'organizationInvites'), where('organizationId', '==', organizationId), where('active', '==', true)))
  return snapshot.docs.map(mapInvitation).filter((value): value is OrganizationInvitation => value !== null)
}

export async function listOrganizationMembers(organizationId: string): Promise<OrganizationMember[]> {
  if (!db) return []
  const snapshot = await getDocs(collection(db, 'organizations', organizationId, 'members'))
  return snapshot.docs.map((item) => mapAdministrator(item.id, item.data())).filter((value): value is OrganizationMember => value !== null)
}

export async function updateOrganizationMemberRole(organizationId: string, member: OrganizationMember, role: Exclude<OrganizationRole, 'primary_admin'>): Promise<void> {
  if (!db) throw new Error('Firebase is not configured.')
  if (member.role === 'primary_admin') throw new Error('The primary administrator cannot be demoted.')
  await updateDoc(doc(db, 'organizations', organizationId, 'members', member.id), { role })
}

export async function updateOrganizationMemberDisplayName(organizationId: string, memberId: string, displayName: string): Promise<void> {
  if (!db) throw new Error('Firebase is not configured.')
  const trimmed = displayName.trim()
  if (!trimmed) throw new Error('Administrator name is required.')
  if (trimmed.length > 100) throw new Error('Administrator name must be 100 characters or fewer.')
  await updateDoc(doc(db, 'organizations', organizationId, 'members', memberId), { displayName: trimmed })
}

export function organizationInviteUrl(code: string): string {
  return `https://thespaces.arcinteractive.studio/organization-invite/${encodeURIComponent(code)}`
}
