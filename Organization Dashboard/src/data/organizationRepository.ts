import { collection, doc, getDoc, getDocs, limit, query, runTransaction, serverTimestamp, setDoc, updateDoc, where } from 'firebase/firestore'
import { auth, db, storage } from './firebase'
import { mapAdministrator, mapOrganization, mapOrganizationDocument, mapSpace, mergeAdministratorProfile } from './mappers'
import type { DashboardData, Organization, SpaceDashboardActivity, SpaceDashboardInvite, SpaceDashboardMember, SpaceDashboardSpace, SpaceMemberRole, SpaceWorkspaceRecord } from '../types'

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
    const allowedModuleIds = organizationId
      ? allowedSpaceModules(organizations.find((organization) => organization.id === organizationId)?.entitlements.enabledModuleIds ?? [])
      : ['general', 'announcements', 'rooms', 'photos', 'files', 'polls', 'events', 'lists', 'notes', 'members', 'settings']
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
  if (allowed.has('agenda')) { allowed.add('lists'); allowed.add('notes') }
  if (allowed.has('communication')) { allowed.add('announcements'); allowed.add('rooms') }
  return ['general', 'photos', 'files', 'polls', 'events', 'lists', 'notes', 'announcements', 'rooms', 'members', 'settings'].filter((module) => allowed.has(module))
}

export async function listSpaceDashboardActivity(userId: string, spaceId: string): Promise<SpaceDashboardActivity[]> {
  if (!db) return []
  const snapshot = await getDocs(query(collection(db, 'activity'), where('visibleTo', 'array-contains', userId), limit(40)))
  const entries = snapshot.docs.map((document) => {
    const data = document.data()
    if (data.spaceId !== spaceId) return null
    const timestamp = data.createdAt && typeof data.createdAt.toDate === 'function' ? data.createdAt.toDate() : null
    const actorName = typeof data.actorName === 'string' ? data.actorName.trim() : ''
    if (!actorName || actorName === 'Deleted User') return null
    return { id: document.id, title: typeof data.title === 'string' ? data.title : 'updated this Space', actorName, createdAt: timestamp }
  }).filter((value): value is SpaceDashboardActivity => value !== null).sort((a, b) => Number(b.createdAt) - Number(a.createdAt))
  const seen = new Set<string>()
  return entries.filter((entry) => {
    const date = entry.createdAt ? entry.createdAt.toDateString() : ''
    const key = `${entry.actorName}|${entry.title}|${date}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).slice(0, 6)
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

type WorkspaceCollection = 'events' | 'lists' | 'notes' | 'announcements' | 'rooms' | 'polls' | 'files' | 'photos'

const asDate = (value: unknown): Date | null => value && typeof value === 'object' && typeof (value as { toDate?: unknown }).toDate === 'function'
  ? (value as { toDate: () => Date }).toDate() : null
const text = (value: unknown) => typeof value === 'string' ? value.trim() : ''
const objectList = (value: unknown): Record<string, unknown>[] => Array.isArray(value)
  ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object') : []

function bytesFromBase64(value: string) {
  const binary = atob(value)
  return Uint8Array.from(binary, character => character.charCodeAt(0))
}

async function spaceCryptoKey(spaceId: string): Promise<CryptoKey | null> {
  if (!db) return null
  const snapshot = await getDoc(doc(db, 'spaces', spaceId, 'encryption', 'key')).catch(() => null)
  const keyBase64 = text(snapshot?.data()?.keyBase64)
  if (!keyBase64 || !globalThis.crypto?.subtle) return null
  try {
    return await crypto.subtle.importKey('raw', bytesFromBase64(keyBase64), { name: 'AES-GCM' }, false, ['decrypt'])
  } catch { return null }
}

async function decryptJson<T>(data: Record<string, unknown>, key: CryptoKey | null): Promise<T | null> {
  const ciphertext = text(data.ciphertext)
  const nonce = text(data.nonce)
  if (!key || !ciphertext || !nonce) return null
  try {
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytesFromBase64(nonce) }, key, bytesFromBase64(ciphertext))
    return JSON.parse(new TextDecoder().decode(plain)) as T
  } catch { return null }
}

async function decryptText(ciphertext: unknown, nonce: unknown, key: CryptoKey | null) {
  if (!key || !text(ciphertext) || !text(nonce)) return ''
  try {
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytesFromBase64(text(nonce)) }, key, bytesFromBase64(text(ciphertext)))
    return new TextDecoder().decode(plain)
  } catch { return '' }
}

async function decryptStoredBytesForFile(spaceId: string, fileId: string, storagePath: string, nonce: string, key: CryptoKey | null, maxBytes: number) {
  if (!key || !spaceId || !fileId || !storagePath || !nonce) throw new Error('The encrypted file metadata is incomplete.')
  const idToken = await auth?.currentUser?.getIdToken()
  if (!idToken) throw new Error('Your sign-in session is unavailable.')
  const response = await fetch(
    `/api/workspace-assets?spaceId=${encodeURIComponent(spaceId)}&fileId=${encodeURIComponent(fileId)}`,
    { headers: { Authorization: `Bearer ${idToken}` } },
  )
  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    throw new Error(`Storage request failed (${response.status})${detail ? `: ${detail}` : '.'}`)
  }
  const encodedCiphertext = await response.text()
  if (encodedCiphertext.length > Math.ceil(maxBytes * 4 / 3)) throw new Error('This file is too large to open in the dashboard.')
  try {
    // Native uploads persist the AES-GCM ciphertext as Base64 text in Storage.
    // Decode that transport representation before handing the bytes to Web Crypto.
    const ciphertext = bytesFromBase64(encodedCiphertext.replace(/\s/g, ''))
    return await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytesFromBase64(nonce) }, key, ciphertext)
  } catch {
    throw new Error('The file could not be decrypted with this Space’s key.')
  }
}

async function decryptStoredBytes(_storagePath: string, _nonce: string, _key: CryptoKey | null, _maxBytes: number) {
  throw new Error('Media assets are not available in the workspace dashboard.')
}

export async function openWorkspaceAsset(spaceId: string, asset: NonNullable<SpaceWorkspaceRecord['asset']>) {
  if (!asset.recordId) throw new Error('This file is missing its dashboard reference.')
  const key = await spaceCryptoKey(spaceId)
  const decrypted = await decryptStoredBytesForFile(spaceId, asset.recordId, asset.storagePath, asset.nonce, key, 250 * 1024 * 1024)
  return URL.createObjectURL(new Blob([decrypted], { type: asset.mimeType || 'application/octet-stream' }))
}

export async function listSpaceWorkspaceRecords(spaceId: string, collectionName: WorkspaceCollection): Promise<SpaceWorkspaceRecord[]> {
  if (!db) return []
  const sourceCollection = collectionName === 'photos' ? 'messages' : collectionName
  const snapshot = await getDocs(collection(db, 'spaces', spaceId, sourceCollection))
  const needsKey = collectionName === 'lists' || collectionName === 'notes' || collectionName === 'rooms' || collectionName === 'files' || collectionName === 'photos'
  const key = needsKey ? await spaceCryptoKey(spaceId) : null
  const values = await Promise.all(snapshot.docs.map(async (item): Promise<SpaceWorkspaceRecord | null> => {
    const data = item.data()
    if ((typeof data.spaceId === 'string' && data.spaceId !== spaceId) || data.deleted === true) return null
    const metadata: string[] = []
    const createdAt = asDate(collectionName === 'events' ? data.startDate ?? data.createdAt : data.createdAt)
    const updatedAt = asDate(data.updatedAt)

    if (collectionName === 'files') {
      const name = text(data.nameCiphertextBase64) ? await decryptText(data.nameCiphertextBase64, data.nameNonceBase64, key) : text(data.name)
      const size = typeof data.fileSize === 'number' ? data.fileSize : 0
      if (text(data.mimeType)) metadata.push(text(data.mimeType))
      if (size) metadata.push(new Intl.NumberFormat(undefined, { style: 'unit', unit: 'megabyte', maximumFractionDigits: 1 }).format(size / (1024 * 1024)))
      if (text(data.uploadedByName)) metadata.push(`Shared by ${text(data.uploadedByName)}`)
      return { id: item.id, title: name || 'Untitled file', detail: text(data.fileExtension).toUpperCase(), metadata, createdAt, updatedAt, asset: { storagePath: text(data.storagePath) || `spaces/${spaceId}/files/${item.id}.enc`, nonce: text(data.nonceBase64), mimeType: text(data.mimeType) || 'application/octet-stream', filename: name || 'file', recordId: item.id } }
    }

    if (collectionName === 'photos') {
      const mediaItems = objectList(data.mediaItems)
      const hasMedia = mediaItems.length > 0 || text(data.mediaId) || text(data.mediaStoragePath) || text(data.storagePath)
      if (!hasMedia) return null
      const type = text(data.mediaType) || text(data.type) || 'Media'
      const itemCount = mediaItems.length || 1
      metadata.push(`${itemCount} item${itemCount === 1 ? '' : 's'}`)
      if (text(data.senderName)) metadata.push(`Shared by ${text(data.senderName)}`)
      const caption = await decryptText(data.captionCiphertextBase64 ?? data.ciphertextBase64, data.captionNonceBase64 ?? data.nonce, key)
      const firstMedia = mediaItems[0] ?? data
      const assetPath = text(firstMedia.storagePath) || text(firstMedia.mediaStoragePath) || text(data.storagePath) || text(data.mediaStoragePath)
      const assetNonce = text(firstMedia.nonce) || text(firstMedia.mediaNonceBase64) || text(data.nonce) || text(data.mediaNonceBase64)
      const thumbnailPath = text(firstMedia.thumbnailStoragePath) || text(data.thumbnailStoragePath)
      const thumbnailNonce = text(firstMedia.thumbnailNonce) || text(firstMedia.thumbnailNonceBase64) || text(data.thumbnailNonce) || text(data.thumbnailNonceBase64)
      const thumbnailBytes = thumbnailPath && thumbnailNonce ? await decryptStoredBytes(thumbnailPath, thumbnailNonce, key, 4 * 1024 * 1024).catch(() => null) : null
      const thumbnailUrl = thumbnailBytes ? URL.createObjectURL(new Blob([thumbnailBytes], { type: 'image/jpeg' })) : undefined
      return { id: item.id, title: `${type[0].toUpperCase()}${type.slice(1)} shared`, detail: caption, metadata, createdAt, updatedAt, asset: assetPath && assetNonce ? { storagePath: assetPath, nonce: assetNonce, mimeType: text(firstMedia.mimeType) || text(data.mimeType) || 'application/octet-stream', filename: `${type.toLowerCase()}-${item.id}`, thumbnailUrl } : undefined }
    }

    if (collectionName === 'events') {
      const startDate = asDate(data.startDate)
      const endDate = asDate(data.endDate)
      const allDay = data.allDay === true
      if (startDate) metadata.push(allDay ? 'All day' : new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(startDate))
      if (!allDay && endDate) metadata.push(`Ends ${new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(endDate)}`)
      if (text(data.location)) metadata.push(text(data.location))
      return { id: item.id, title: text(data.title) || 'Untitled event', detail: text(data.description), metadata, createdAt, updatedAt, allDay, endDate }
    }

    if (collectionName === 'polls') {
      const options = objectList(data.options).map(option => ({ id: text(option.id), label: text(option.text) })).filter(option => option.id && option.label)
      const votes = await getDocs(collection(item.ref, 'votes')).catch(() => null)
      const voteDocs = votes?.docs ?? []
      const totalVotes = voteDocs.length
      if (data.anonymous === true) metadata.push('Anonymous voting')
      if (data.allowMultipleVotes === true) metadata.push('Multiple choices')
      const closesAt = asDate(data.closesAt)
      if (closesAt) metadata.push(closesAt.getTime() <= Date.now() ? 'Closed' : `Closes ${formatShortDate(closesAt)}`)
      return { id: item.id, title: text(data.question) || 'Untitled poll', detail: '', metadata, createdAt, updatedAt, totalVotes, pollResults: options.map(option => {
        const votesForOption = voteDocs.filter(vote => stringList(vote.data().optionIds).includes(option.id)).length
        return { label: option.label, votes: votesForOption, percent: totalVotes ? Math.round((votesForOption / totalVotes) * 100) : 0 }
      }) }
    }

    if (collectionName === 'announcements') {
      if (data.isPinned === true) metadata.push('Pinned')
      const attachments = objectList(data.attachments)
      const references = objectList(data.references)
      if (attachments.length) metadata.push(`${attachments.length} attachment${attachments.length === 1 ? '' : 's'}`)
      if (references.length) metadata.push(`${references.length} linked item${references.length === 1 ? '' : 's'}`)
      if (data.commentsEnabled === true) metadata.push(`${objectList(data.comments).length} comment${objectList(data.comments).length === 1 ? '' : 's'}`)
      return {
        id: item.id, title: text(data.title) || 'Untitled announcement', detail: text(data.body), metadata, createdAt, updatedAt,
        author: text(data.authorName) || 'Member',
        reactions: objectList(data.reactions).map(reaction => ({ emoji: text(reaction.emoji), count: stringList(reaction.userIds).length })).filter(reaction => reaction.emoji),
        comments: objectList(data.comments).map(comment => ({ author: text(comment.authorName) || 'Member', body: text(comment.body) })).filter(comment => comment.body),
      }
    }

    if (collectionName === 'rooms') {
      const members = stringList(data.memberIds)
      if (members.length) metadata.push(`${members.length} member${members.length === 1 ? '' : 's'}`)
      if (data.isPrivate === true) metadata.push('Private')
      const messages = await getDocs(collection(item.ref, 'messages')).catch(() => null)
      const messageDocs = messages?.docs ?? []
      if (messageDocs.length) metadata.push(`${messageDocs.length} message${messageDocs.length === 1 ? '' : 's'}`)
      const messageKey = data.keyMode === 'space-member-key-v1' ? key : await roomCryptoKey(spaceId, item.id)
      const latest = [...messageDocs].sort((a, b) => Number(asDate(b.data().createdAt)) - Number(asDate(a.data().createdAt)))[0]
      const latestBody = latest ? await decryptText(latest.data().ciphertext, latest.data().nonce, messageKey) : ''
      return { id: item.id, title: text(data.name) || 'Untitled room', detail: latestBody || text(data.topic), metadata, createdAt, updatedAt, author: latest ? text(latest.data().senderName) || undefined : undefined }
    }

    if (collectionName === 'lists') {
      const payload = await decryptJson<{ title?: string; sections?: { title?: string }[] }>(data, key)
      if (!payload) return { id: item.id, title: 'Encrypted list', detail: 'This list could not be opened for this account.', metadata: ['Encrypted'], createdAt, updatedAt }
      const items = await getDocs(collection(item.ref, 'items')).catch(() => null)
      const decodedItems = await Promise.all((items?.docs ?? []).map(async listItem => {
        const itemPayload = await decryptJson<{ title?: string; notes?: string }>(listItem.data(), key)
        return { title: text(itemPayload?.title) || 'Untitled item', completed: listItem.data().isCompleted === true, dueDate: asDate(listItem.data().dueDate), notes: text(itemPayload?.notes) }
      }))
      const completed = decodedItems.filter(listItem => listItem.completed).length
      metadata.push(`${completed}/${decodedItems.length} complete`)
      if (payload.sections?.length) metadata.push(`${payload.sections.length} section${payload.sections.length === 1 ? '' : 's'}`)
      return { id: item.id, title: text(payload.title) || 'Untitled list', detail: decodedItems.filter(listItem => !listItem.completed).slice(0, 3).map(listItem => listItem.title).join(' · '), metadata, createdAt, updatedAt, itemSummary: { completed, total: decodedItems.length, items: decodedItems.map(({ title, completed, dueDate }) => ({ title, completed, dueDate })) } }
    }

    const payload = await decryptJson<{ title?: string; markdown?: string; attachments?: unknown[]; links?: unknown[] }>(data, key)
    if (!payload) return { id: item.id, title: 'Encrypted note', detail: 'This note could not be opened for this account.', metadata: ['Encrypted'], createdAt, updatedAt }
    if (Array.isArray(payload.attachments) && payload.attachments.length) metadata.push(`${payload.attachments.length} attachment${payload.attachments.length === 1 ? '' : 's'}`)
    if (Array.isArray(payload.links) && payload.links.length) metadata.push(`${payload.links.length} linked item${payload.links.length === 1 ? '' : 's'}`)
    return { id: item.id, title: text(payload.title) || 'Untitled note', detail: text(payload.markdown), metadata, createdAt, updatedAt }
  }))
  return values.filter((value): value is SpaceWorkspaceRecord => value !== null).sort((a, b) => Number(b.updatedAt ?? b.createdAt) - Number(a.updatedAt ?? a.createdAt))
}

const formatShortDate = (value: Date) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(value)

async function roomCryptoKey(spaceId: string, roomId: string) {
  if (!db) return null
  const snapshot = await getDoc(doc(db, 'spaces', spaceId, 'rooms', roomId, 'encryption', 'key')).catch(() => null)
  const keyBase64 = text(snapshot?.data()?.keyBase64)
  if (!keyBase64 || !globalThis.crypto?.subtle) return null
  try { return await crypto.subtle.importKey('raw', bytesFromBase64(keyBase64), { name: 'AES-GCM' }, false, ['decrypt']) } catch { return null }
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
