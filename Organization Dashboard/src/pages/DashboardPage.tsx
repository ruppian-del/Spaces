import { useEffect, useState } from 'react'
import { Archive, Building2, Database, ExternalLink, LayoutGrid, LogOut, Puzzle, Settings, ShieldCheck, Users } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Brand } from '../components/Brand'
import { StateView } from '../components/StateView'
import { useAuth } from '../auth/AuthContext'
import { listOrganizations, loadDashboard, resolveAdministratorProfiles } from '../data/organizationRepository'
import { mergeAdministratorAuthDisplayName } from '../data/mappers'
import type { DashboardData, LoadState, Organization } from '../types'

const MODULES: Record<string, { title: string; group: string }> = {
  general: { title: 'Space Pings', group: 'Foundation' }, events: { title: 'Events', group: 'Foundation' }, polls: { title: 'Polls', group: 'Foundation' },
  activity: { title: 'Activity', group: 'Foundation' }, members: { title: 'Members', group: 'Foundation' }, settings: { title: 'Settings', group: 'Foundation' },
  announcements: { title: 'Announcements', group: 'Communication' }, rooms: { title: 'Rooms', group: 'Communication' },
  lists: { title: 'Lists', group: 'Agenda' }, notes: { title: 'Notes', group: 'Agenda' }, photos: { title: 'Media', group: 'Content' }, files: { title: 'Files', group: 'Content' },
}

const FOUNDATION_ENTITLEMENTS: Organization['entitlements'] = {
  peopleCapacity: 250,
  activeSpaceCapacity: 10,
  enabledModuleIds: ['general', 'events', 'polls', 'activity', 'members', 'settings'],
  mediaStorageCapacityBytes: 10 * 1024 ** 3,
}

function displayedEntitlements(organization: Organization): Organization['entitlements'] {
  const stored = organization.entitlements
  const hasNoUsableConfiguration = stored.peopleCapacity == null
    && stored.activeSpaceCapacity == null
    && stored.enabledModuleIds.length === 0
    && stored.mediaStorageCapacityBytes == null
  return hasNoUsableConfiguration ? FOUNDATION_ENTITLEMENTS : stored
}

function formatBytes(bytes: number) {
  if (bytes === 0) return '0 GB'
  const gb = bytes / 1024 ** 3
  return `${gb >= 10 ? gb.toFixed(0) : gb.toFixed(1)} GB`
}

function UsageCard({ label, used, capacity, icon }: { label: string; used: number; capacity: number | null; icon: React.ReactNode }) {
  const percent = capacity && capacity > 0 ? Math.min((used / capacity) * 100, 100) : 0
  return <article className="usage-card"><div className="usage-heading"><span className="usage-icon">{icon}</span><span className="usage-percent">{capacity ? `${Math.round(percent)}%` : '—'}</span></div><p>{label}</p><div className="usage-values"><strong>{label === 'Pooled storage' ? formatBytes(used) : used.toLocaleString()}</strong><span>of {capacity === null ? 'Not configured' : label === 'Pooled storage' ? formatBytes(capacity) : capacity.toLocaleString()}</span></div><div className="progress" aria-label={`${label}: ${Math.round(percent)} percent used`}><span style={{ width: `${percent}%` }} /></div></article>
}

export function DashboardContent({ data }: { data: DashboardData }) {
  const { organization: org, administrators, spaces } = data
  const entitlements = displayedEntitlements(org)
  const modules = entitlements.enabledModuleIds.map((id) => ({ id, ...(MODULES[id] ?? { title: id, group: 'Additional' }) }))
  const activeSpaces = spaces.filter((space) => !space.isArchived)
  const uniqueMemberCount = new Set(spaces.flatMap((space) => space.memberIds)).size
  return <><div className="dashboard-heading"><div><span className="eyebrow">Organization overview</span><h1>{org.name}</h1>{org.description && <p>{org.description}</p>}<div className="status-line"><span className={`status-dot ${org.status}`} /> {org.status === 'active' ? 'Active' : 'Suspended'}</div></div><Link className="secondary-button" to={`/organizations/${org.id}/settings`}><Settings size={17} /> Settings</Link></div>
    <section aria-labelledby="capacity-heading"><div className="section-heading"><div><h2 id="capacity-heading">Capacity at a glance</h2><p>Unique members count once across every organization-owned Space.</p></div><span>Updated just now</span></div><div className="usage-grid"><UsageCard label="Unique members" used={uniqueMemberCount} capacity={entitlements.peopleCapacity} icon={<Users />} /><UsageCard label="Active Spaces" used={activeSpaces.length} capacity={entitlements.activeSpaceCapacity} icon={<LayoutGrid />} /><UsageCard label="Pooled storage" used={org.usage.mediaStorageBytes} capacity={entitlements.mediaStorageCapacityBytes} icon={<Database />} /></div></section>
    <div className="dashboard-columns"><section className="panel"><div className="panel-heading"><div><span className="panel-icon violet"><Puzzle /></span><div><h2>Enabled modules</h2><p>{modules.length} entitlements available</p></div></div></div><div className="module-list">{modules.map((module) => <div className="module-row" key={module.id}><span className="module-check">✓</span><span><strong>{module.title}</strong><small>{module.group}</small></span><span className="enabled-label">Enabled</span></div>)}</div></section>
      <div className="right-stack"><section className="panel"><div className="panel-heading"><div><span className="panel-icon blue"><ShieldCheck /></span><div><h2>Administrators</h2><p>{administrators.length} people can manage this organization</p></div></div></div>{administrators.length === 0 ? <p className="inline-empty">No administrators were returned.</p> : <div className="admin-list">{administrators.map((admin) => <div className="admin-row" key={admin.id}><span className="person-avatar">{admin.displayName.split(' ').map((part) => part[0]).slice(0, 2).join('')}</span><span><strong>{admin.displayName}</strong><small>{admin.email ?? 'No email available'}</small></span><span className="role-pill">{admin.role === 'primary_admin' ? 'Primary admin' : 'Admin'}</span></div>)}</div>}</section>
        <section className="panel"><div className="panel-heading"><div><span className="panel-icon mint"><Building2 /></span><div><h2>Organization Spaces</h2><p>{activeSpaces.length} active · {spaces.length - activeSpaces.length} archived</p></div></div></div>{spaces.length === 0 ? <p className="inline-empty">No Spaces have been attached to this organization.</p> : <div className="space-list">{spaces.map((space) => <div className="space-row" key={space.id}><span className="space-emoji">{space.emoji}</span><span><strong>{space.name}</strong><small>{space.memberCount} {space.memberCount === 1 ? 'member' : 'members'}</small></span>{space.isArchived ? <span className="archive-label"><Archive size={14} /> Archived</span> : <span className="active-label">Active</span>}</div>)}</div>}</section></div></div>
    <aside className="boundary-note"><ShieldCheck /><div><strong>Private by design</strong><p>This dashboard shows organization metadata only. Messages, Rooms, Announcements, Media, Files, Polls, Events, Lists, Notes, and other Space content stay in the native apps.</p></div></aside></>
}

export function DashboardPage() {
  const { organizationId } = useParams(); const location = useLocation(); const { user, signOutUser } = useAuth(); const [state, setState] = useState<LoadState>({ kind: 'loading' })
  async function load() {
    setState({ kind: 'loading' })
    try {
      const routedOrganization = (location.state as { organization?: Organization } | null)?.organization
      const organization = routedOrganization?.id === organizationId
        ? routedOrganization
        : (await listOrganizations(user!.uid)).find((item) => item.id === organizationId)
      if (!organization) { setState({ kind: 'permission-denied' }); return }
      const loadedData = await loadDashboard(organization)
      const data = { ...loadedData, administrators: loadedData.administrators.map((administrator) => mergeAdministratorAuthDisplayName(administrator, user!)) }
      const currentMember = data.administrators.some((admin) => admin.userId === user!.uid)
      if (!currentMember) { setState({ kind: 'permission-denied' }); return }
      setState({ kind: 'ready', data })
      const administrators = (await resolveAdministratorProfiles(data.administrators)).map((administrator) => mergeAdministratorAuthDisplayName(administrator, user!))
      setState({ kind: 'ready', data: { ...data, administrators } })
    } catch (error: unknown) {
      const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : ''
      setState(code.includes('permission-denied') ? { kind: 'permission-denied' } : { kind: 'error', message: 'The latest organization details could not be retrieved. Check your connection and try again.' })
    }
  }
  useEffect(() => { void load() }, [organizationId, user]) // eslint-disable-line react-hooks/exhaustive-deps
  return <div className="app-page"><header className="topbar"><Link to="/organizations" className="brand-link"><Brand compact /></Link><div className="topbar-center"><Building2 size={17} /> Organization administration</div><div className="topbar-actions"><a className="help-link" href="mailto:support@arcinteractive.com">Help <ExternalLink size={14} /></a><button className="icon-button" aria-label="Sign out" onClick={() => void signOutUser()}><LogOut size={19} /></button></div></header><main className="dashboard">{state.kind === 'ready' ? <DashboardContent data={state.data} /> : <StateView state={state} onRetry={state.kind === 'error' ? load : undefined} />}</main></div>
}
