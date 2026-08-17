import { collection, doc, getDoc, getDocs, query, where } from 'firebase/firestore'
import { db } from './firebase'
import { mapAdministrator, mapOrganization, mapOrganizationDocument, mapSpace, mergeAdministratorProfile } from './mappers'
import type { DashboardData, Organization } from '../types'

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
