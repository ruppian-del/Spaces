import { collection, doc, getDoc, getDocs, limit, query, runTransaction, serverTimestamp, setDoc, updateDoc, where } from 'firebase/firestore'
import { db } from './firebase'
import { mapAdministrator, mapOrganization, mapOrganizationDocument, mapSpace, mergeAdministratorProfile } from './mappers'
import type { DashboardData, Organization, SpaceDashboardActivity, SpaceDashboardInvite, SpaceDashboardMember, SpaceDashboardSpace, SpaceMemberRole } from '../types'

const stringList = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
const spaceCacheKey = (userId: string) => `thespaces.space-dashboard.${userId}`

export function readCachedSpaceDashboards(userId: string): SpaceDashboardSpace[] {
  try {
    const raw = sessionStorage.getItem(spaceCacheKey(userId))
    const cached = raw ? JSON.parse(raw) : []
    return Array.isArray(cached) ? cached.filter((space): space is SpaceDashboardSpace => space && typeof space.id === 'string' && typeof space.name === 'string') : []
  } catch { return [] }
}

function cacheSpaceDashboards(userId: string, spaces: SpaceDashboardSpace[]) {
  try { sessionStorage.setItem(spaceCacheKey(userId), JSON.stringify(spaces)) } catch { /* storage is an optional performance cache */ }
}

export async function listAccessibleSpaceDashboards(userId: string): Promise<SpaceDashboardSpace[]> {
  if (!db) return []
  const organizations = await listOrganizations(userId).catch(() => [])
  const organizationIds = new Set(organizations.map((organization) => organization.id))
  const [memberSpaces, organizationSpaces] = await Promise.all([
    getDocs(query(collection(db, 'spaces'), where('memberIds', 'array-contains', userId))),
    Promise.all(organizations.map((organization) => getDocs(query(collection(db!, 'spaces'), where('organizationId', '==', organization.id))))),
  ])
  const candidates = new Map(memberSpaces.docs.map((snapshot) => [snapshot.id, snapshot]))
  organizationSpaces.flatMap((snapshot) => snapshot.docs).forEach((snapshot) => candidates.set(snapshot.id, snapshot))
  const values = await Promise.all([...candidates.values()].map(async (snapshot) => {
    const data = snapshot.data()
    const organizationId = typeof data.organizationId === 'string' ? data.organizationId : null
    const isOrganizationAdmin = organizationId !== null && organizationIds.has(organizationId)
    const member = isOrganizationAdmin ? null : await getDoc(doc(db!, 'spaces', snapshot.id, 'members', userId)).catch(() => null)
    const memberRole = member?.exists() && typeof member.data().role === 'string' ? member.data().role : null
    if (!isOrganizationAdmin && memberRole !== 'owner' && memberRole !== 'admin') return null
    const allowedModuleIds = allowedSpaceModules(organizationId ? organizations.find((organization) => organization.id === organizationId)?.entitlements.enabledModuleIds ?? [] : [])
    const enabledModuleIds = stringList(data.enabledModules).filter((module) => allowedModuleIds.includes(module))
    return {
      id: snapshot.id,
      organizationId,
      name: typeof data.name === 'string' && data.name.trim() ? data.name : 'Untitled Space',
      emoji: typeof data.emoji === 'string' && data.emoji.trim() ? data.emoji : '🏠',
      description: typeof data.description === 'string' ? data.description : '',
      memberCount: stringList(data.memberIds).length,
      enabledModuleIds,
      moduleOrder: stringList(data.moduleOrder).filter((module) => allowedModuleIds.includes(module)),
      tintHex: typeof data.tintHex === 'string' && /^#[0-9a-f]{6}$/i.test(data.tintHex) ? data.tintHex : '#4f46e5',
      template: typeof data.template === 'string' && data.template.trim() ? data.template : 'Custom',
      allowedModuleIds,
      role: isOrganizationAdmin ? 'organization_admin' : memberRole,
    } satisfies SpaceDashboardSpace
  }))
  const resolved = values.filter((value): value is SpaceDashboardSpace => value !== null).sort((a, b) => a.name.localeCompare(b.name))
  cacheSpaceDashboards(userId, resolved)
  return resolved
}

function allowedSpaceModules(entitlements: string[]): string[] {
  const allowed = new Set(entitlements)
  if (allowed.has('content')) { allowed.add('photos'); allowed.add('files') }
  return ['general', 'photos', 'files', 'polls', 'events', 'members', 'settings'].filter((module) => allowed.has(module))
}

export async function listSpaceDashboardActivity(userId: string, spaceId: string): Promise<SpaceDashboardActivity[]> {
  if (!db) return []
  const snapshot = await getDocs(query(collection(db, 'activity'), where('visibleTo', 'array-contains', userId), limit(40)))
  return snapshot.docs.map((document) => {
    const data = document.data()
    if (data.spaceId !== spaceId) return null
    const timestamp = data.createdAt && typeof data.createdAt.toDate === 'function' ? data.createdAt.toDate() : null
    return { id: document.id, title: typeof data.title === 'string' ? data.title : 'updated this Space', actorName: typeof data.actorName === 'string' ? data.actorName : 'Member', createdAt: timestamp }
  }).filter((value): value is SpaceDashboardActivity => value !== null).sort((a, b) => Number(b.createdAt) - Number(a.createdAt)).slice(0, 6)
}

const spaceMemberRoles = new Set<SpaceMemberRole>(['owner', 'admin', 'moderator', 'member', 'guest'])
const spaceMemberRoleOrder: Record<SpaceMemberRole, number> = { owner: 0, admin: 1, moderator: 2, member: 3, guest: 4 }

export async function listSpaceDashboardMembers(spaceId: string): Promise<SpaceDashboardMember[]> {
  if (!db) return []
  const snapshot = await getDocs(collection(db, 'spaces', spaceId, 'members'))
  return snapshot.docs.map((member) => {
    const data = member.data()
    const role = typeof data.role === 'string' && spaceMemberRoles.has(data.role as SpaceMemberRole) ? data.role as SpaceMemberRole : 'member'
    return {
      id: member.id,
      displayName: typeof data.displayName === 'string' && data.displayName.trim() ? data.displayName : 'Member',
      emojiAvatar: typeof data.emojiAvatar === 'string' && data.emojiAvatar.trim() ? data.emojiAvatar : '👤',
      role,
    }
  }).sort((a, b) => spaceMemberRoleOrder[a.role] - spaceMemberRoleOrder[b.role] || a.displayName.localeCompare(b.displayName))
}

export async function updateSpaceMemberRole(spaceId: string, memberId: string, role: Exclude<SpaceMemberRole, 'owner'>) {
  if (!db) throw new Error('Firebase is not configured.')
  await updateDoc(doc(db, 'spaces', spaceId, 'members', memberId), { role })
}

export async function removeSpaceMember(spaceId: string, memberId: string) {
  if (!db) throw new Error('Firebase is not configured.')
  const spaceReference = doc(db, 'spaces', spaceId)
  const memberReference = doc(db, 'spaces', spaceId, 'members', memberId)
  await runTransaction(db, async (transaction) => {
    const space = await transaction.get(spaceReference)
    if (!space.exists()) throw new Error('This Space no longer exists.')
    const memberIds = stringList(space.data().memberIds).filter((id) => id !== memberId)
    transaction.update(spaceReference, { memberIds, updatedAt: serverTimestamp() })
    transaction.delete(memberReference)
  })
}

export async function updateSpaceDashboardSettings(spaceId: string, input: Pick<SpaceDashboardSpace, 'name' | 'emoji' | 'description' | 'tintHex' | 'template' | 'enabledModuleIds' | 'moduleOrder'>) {
  if (!db) throw new Error('Firebase is not configured.')
  await updateDoc(doc(db, 'spaces', spaceId), {
    name: input.name,
    emoji: input.emoji,
    description: input.description,
    tintHex: input.tintHex,
    template: input.template,
    enabledModules: input.enabledModuleIds,
    moduleOrder: input.moduleOrder,
    updatedAt: serverTimestamp(),
  })
}


function spaceInviteCode(): string {
  const characters = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  return Array.from({ length: 6 }, () => characters[Math.floor(Math.random() * characters.length)]).join('')
}

export async function createSpaceInvite(space: SpaceDashboardSpace, creatorId: string): Promise<SpaceDashboardInvite> {
  if (!db) throw new Error('Firebase is not configured.')
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const code = spaceInviteCode()
    const reference = doc(db, 'spaceInvites', code)
    if ((await getDoc(reference)).exists()) continue
    const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
    await setDoc(reference, { code, spaceId: space.id, spaceName: space.name, spaceEmoji: space.emoji, createdBy: creatorId, createdAt: serverTimestamp(), expiresAt, maxUses: 25, usedCount: 0, active: true })
    return { code, expiresAt }
  }
  throw new Error('An invite code could not be created.')
}

export async function listOrganizations(userId: string): Promise<Organization[]> {
  if (!db) return []
  const snapshot = await getDocs(query(collection(db, 'organizations'), where('memberIds', 'array-contains', userId)))
  return snapshot.docs.map(mapOrganizationDocument).filter((value): value is Organization => value !== null)
    .sort((a, b) => a.name.localeCompare(b.name))
}

export async function loadDashboard(organization: Organization): Promise<DashboardData> {
  if (!db) throw new Error('Firebase is not configured.')
  const firestore = db
  const [organizationSnapshot, memberSnapshot, spaceSnapshot] = await Promise.all([
    getDoc(doc(firestore, 'organizations', organization.id)),
    getDocs(collection(firestore, 'organizations', organization.id, 'members')),
    getDocs(query(collection(firestore, 'spaces'), where('organizationId', '==', organization.id))),
  ])
  if (!organizationSnapshot.exists()) throw new Error('Organization was not found.')
  const currentOrganization = mapOrganization(organizationSnapshot.id, organizationSnapshot.data())
  if (!currentOrganization) throw new Error('Organization data is invalid.')
  const storedAdministrators = memberSnapshot.docs
    .map((doc) => mapAdministrator(doc.id, doc.data()))
    .filter((member) => member?.role === 'primary_admin' || member?.role === 'admin')
    .filter((member) => member !== null)
  const administrators = storedAdministrators
    .sort((a, b) => (a.role === b.role ? a.displayName.localeCompare(b.displayName) : a.role === 'primary_admin' ? -1 : 1))
  const spaces = spaceSnapshot.docs.map((doc) => mapSpace(doc.id, doc.data()))
    .sort((a, b) => Number(a.isArchived) - Number(b.isArchived) || a.name.localeCompare(b.name))
  return { organization: currentOrganization, administrators, spaces }
}

export async function resolveAdministratorProfiles(
  administrators: DashboardData['administrators'],
): Promise<DashboardData['administrators']> {
  if (!db || administrators.length === 0) return administrators
  const firestore = db
  return (await Promise.all(administrators.map(async (administrator) => {
    const profile = await getDoc(doc(firestore, 'users', administrator.userId)).catch(() => null)
    return mergeAdministratorProfile(administrator, profile?.exists() ? profile.data() : null)
  }))).sort((a, b) => (a.role === b.role ? a.displayName.localeCompare(b.displayName) : a.role === 'primary_admin' ? -1 : 1))
}
