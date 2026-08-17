import { useEffect, useState } from 'react'
import { ArrowLeft, Check, Copy, Link as LinkIcon, Plus, ShieldAlert, UserCog } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { Brand } from '../components/Brand'
import { StateView } from '../components/StateView'
import { listOrganizations, resolveAdministratorProfiles } from '../data/organizationRepository'
import { mergeAdministratorAuthDisplayName } from '../data/mappers'
import {
  createAdministratorInvitation, listOrganizationMembers, listPendingInvitations, organizationInviteUrl,
  updateOrganizationIdentity, updateOrganizationMemberRole, canManageOrganizationSettings,
} from '../data/organizationMutations'
import { validateOrganizationIdentity, type IdentityErrors } from '../data/organizationValidation'
import type { LoadState, Organization, OrganizationIdentityInput, OrganizationInvitation, OrganizationMember } from '../types'

function operationError(error: unknown, fallback: string) {
  const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : ''
  if (code.includes('permission-denied')) return 'Permission denied. This action requires the primary administrator and matching backend authorization.'
  if (code.includes('unavailable') || code.includes('network')) return 'Network unavailable. Try again without reloading the page.'
  return error instanceof Error ? error.message : fallback
}

export function OrganizationSettingsPage() {
  const { organizationId } = useParams(); const { user } = useAuth()
  const [loadState, setLoadState] = useState<LoadState>({ kind: 'loading' })
  const [organization, setOrganization] = useState<Organization | null>(null); const [members, setMembers] = useState<OrganizationMember[]>([])
  const [invitations, setInvitations] = useState<OrganizationInvitation[]>([]); const [identity, setIdentity] = useState<OrganizationIdentityInput>({ name: '', description: '', contactEmail: '', website: '', logoDataUrl: '' })
  const [errors, setErrors] = useState<IdentityErrors>({}); const [identityStatus, setIdentityStatus] = useState<string | null>(null)
  const [saving, setSaving] = useState(false); const [inviting, setInviting] = useState(false); const [inviteError, setInviteError] = useState<string | null>(null)
  const [copiedCode, setCopiedCode] = useState<string | null>(null); const [roleError, setRoleError] = useState<string | null>(null)

  async function load() {
    if (!user || !organizationId) return
    setLoadState({ kind: 'loading' })
    try {
      const found = (await listOrganizations(user.uid)).find((item) => item.id === organizationId)
      if (!found) { setLoadState({ kind: 'permission-denied' }); return }
      const [storedMembers, nextInvites] = await Promise.all([listOrganizationMembers(found.id), listPendingInvitations(found.id).catch(() => [])])
      // Settings must resolve administrator names exactly as the dashboard does:
      // member record, then user profile, then the signed-in user's auth profile.
      const nextMembers = (await resolveAdministratorProfiles(storedMembers)).map((member) =>
        mergeAdministratorAuthDisplayName(member, user),
      )
      setOrganization(found); setMembers(nextMembers); setInvitations(nextInvites)
      setIdentity({ name: found.name, description: found.description ?? '', contactEmail: found.contactEmail ?? '', website: found.website ?? '', logoDataUrl: found.logoDataUrl ?? '' })
      setLoadState({ kind: 'ready', data: { organization: found, administrators: nextMembers.filter((member) => member.role !== 'member'), spaces: [] } })
    } catch (error) { setLoadState({ kind: 'error', message: operationError(error, 'Organization settings could not be loaded.') }) }
  }
  useEffect(() => { void load() }, [organizationId, user]) // eslint-disable-line react-hooks/exhaustive-deps
  const currentMember = members.find((member) => member.userId === user?.uid)
  const isPrimary = canManageOrganizationSettings(currentMember)
  function update(field: Exclude<keyof OrganizationIdentityInput, 'logoDataUrl'>, value: string) { setIdentity((current) => ({ ...current, [field]: value })); setErrors((current) => ({ ...current, [field]: undefined })); setIdentityStatus(null) }
  async function chooseLogo(file: File | undefined) {
    if (!file) return
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 250 * 1024) { setErrors((current) => ({ ...current, logoDataUrl: 'Choose a PNG, JPEG, or WebP image under 250 KB.' })); return }
    const reader = new FileReader()
    reader.onload = () => { const logoDataUrl = reader.result; if (typeof logoDataUrl === 'string') { setIdentity((current) => ({ ...current, logoDataUrl })); setErrors((current) => ({ ...current, logoDataUrl: undefined })) } }
    reader.readAsDataURL(file)
  }
  async function saveIdentity() {
    if (!organization || !isPrimary) return
    const nextErrors = validateOrganizationIdentity(identity); setErrors(nextErrors); if (Object.keys(nextErrors).length) return
    setSaving(true); setIdentityStatus(null)
    try { await updateOrganizationIdentity(organization.id, identity); setIdentityStatus('Organization identity saved.') }
    catch (error) { setIdentityStatus(operationError(error, 'Organization identity could not be saved.')) }
    finally { setSaving(false) }
  }
  async function invite() {
    if (!organization || !user || !isPrimary || inviting) return
    setInviting(true); setInviteError(null)
    try { const created = await createAdministratorInvitation(organization, user.uid); setInvitations((current) => [created, ...current]) }
    catch (error) { setInviteError(operationError(error, 'The administrator invitation could not be created.')) }
    finally { setInviting(false) }
  }
  async function copyInvite(invitation: OrganizationInvitation) {
    try { await navigator.clipboard.writeText(organizationInviteUrl(invitation.id)); setCopiedCode(invitation.id); window.setTimeout(() => setCopiedCode(null), 2000) }
    catch { setInviteError('The invitation link could not be copied. Copy the invitation code manually.') }
  }
  async function changeRole(member: OrganizationMember, role: 'admin' | 'member') {
    if (!organization || !isPrimary || member.role === 'primary_admin') return
    setRoleError(null)
    try { await updateOrganizationMemberRole(organization.id, member, role); setMembers((current) => current.map((item) => item.id === member.id ? { ...item, role } : item)) }
    catch (error) { setRoleError(operationError(error, 'The administrator role could not be updated.')) }
  }
  if (loadState.kind !== 'ready') return <div className="app-page"><header className="topbar"><Brand compact /></header><main className="dashboard"><StateView state={loadState} onRetry={loadState.kind === 'error' ? load : undefined} /></main></div>
  if (!organization) return <div className="app-page"><header className="topbar"><Brand compact /></header><main className="dashboard"><StateView state={{ kind: 'error', message: 'Organization settings could not be loaded.' }} onRetry={load} /></main></div>
  return <div className="app-page"><header className="topbar"><Link to={`/organizations/${organization.id}`} className="brand-link"><Brand compact /></Link></header><main className="settings-page"><Link className="back-link" to={`/organizations/${organization.id}`}><ArrowLeft size={16} /> Dashboard</Link><div className="settings-heading"><span className="eyebrow">Organization settings</span><h1>{organization.name}</h1><p>Manage organization identity and administrator access. Capacities and paid module entitlements are read-only.</p></div>{!isPrimary && <div className="form-alert warning"><ShieldAlert /> Only the primary administrator can change identity or administrator roles. You have read-only access.</div>}
    <section className="setup-card"><h2>Organization identity</h2><div className="form-grid"><label className="field full">Organization name <input disabled={!isPrimary} value={identity.name} onChange={(event) => update('name', event.target.value)} aria-invalid={Boolean(errors.name)} />{errors.name && <small className="field-error">{errors.name}</small>}</label><label className="field full">Description <textarea disabled={!isPrimary} rows={4} maxLength={500} value={identity.description} onChange={(event) => update('description', event.target.value)} /></label><label className="field">Contact email <input disabled={!isPrimary} type="email" value={identity.contactEmail} onChange={(event) => update('contactEmail', event.target.value)} />{errors.contactEmail && <small className="field-error">{errors.contactEmail}</small>}</label><label className="field">Website <input disabled={!isPrimary} type="url" value={identity.website} onChange={(event) => update('website', event.target.value)} />{errors.website && <small className="field-error">{errors.website}</small>}</label><label className="field full">Organization logo <input disabled={!isPrimary} type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => void chooseLogo(event.target.files?.[0])} />{errors.logoDataUrl && <small className="field-error">{errors.logoDataUrl}</small>}<small>PNG, JPEG, or WebP up to 250 KB.</small>{identity.logoDataUrl && <img className="logo-preview" src={identity.logoDataUrl} alt="Selected organization logo" />}</label></div>{identityStatus && <div className={`form-alert ${identityStatus.includes('saved') ? 'success' : ''}`} role="status">{identityStatus}</div>}<button className="primary-button" disabled={!isPrimary || saving} onClick={() => void saveIdentity()}>{saving ? 'Saving…' : 'Save identity'}</button></section>
    <section className="setup-card"><div className="card-heading"><div><h2>Administrators</h2><p>The primary administrator is protected from demotion. Supported roles are Primary admin, Admin, and Member.</p></div><button className="primary-button" disabled={!isPrimary || inviting} onClick={() => void invite()}><Plus size={17} /> {inviting ? 'Creating…' : 'Invite administrator'}</button></div>{inviteError && <div className="form-alert" role="alert">{inviteError}</div>}<div className="settings-list">{members.map((member) => <div className="settings-row" key={member.id}><span className="person-avatar"><UserCog /></span><span><strong>{member.displayName}</strong><small>{member.email ?? 'No email available'}</small></span>{member.role === 'primary_admin' ? <span className="role-pill">Primary admin</span> : <label className="compact-select"><span className="sr-only">Role for {member.displayName}</span><select disabled={!isPrimary} value={member.role} onChange={(event) => void changeRole(member, event.target.value as 'admin' | 'member')}><option value="admin">Admin</option><option value="member">Member</option></select></label>}</div>)}</div>{roleError && <div className="form-alert" role="alert">{roleError}</div>}</section>
    <section className="setup-card"><div className="card-heading"><div><h2>Pending invitations</h2><p>{invitations.length === 0 ? 'No active administrator invitations.' : `${invitations.length} active administrator ${invitations.length === 1 ? 'invitation' : 'invitations'}.`}</p></div></div>{invitations.length > 0 && <details className="pending-invite-details"><summary>View invitation codes</summary><div className="settings-list">{invitations.map((invitation) => <div className="settings-row" key={invitation.id}><span className="panel-icon blue"><LinkIcon /></span><span><strong>{invitation.id}</strong><small>Administrator · Pending</small></span><button className="secondary-button" onClick={() => void copyInvite(invitation)}>{copiedCode === invitation.id ? <><Check size={16} /> Copied</> : <><Copy size={16} /> Copy link</>}</button></div>)}</div></details>}<p className="settings-note">New administrator invitations are created individually. Existing invitations remain active until redemption because the current v1.6 backend does not authorize revocation.</p></section>
  </main></div>
}
