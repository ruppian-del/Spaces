import { useEffect, useState } from 'react'
import { Building2, ChevronRight, LogOut } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Brand } from '../components/Brand'
import { StateView } from '../components/StateView'
import { useAuth } from '../auth/AuthContext'
import { listOrganizations } from '../data/organizationRepository'
import type { LoadState, Organization } from '../types'

export function OrganizationSelectPage() {
  const { user, signOutUser } = useAuth(); const navigate = useNavigate()
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [organizations, setOrganizations] = useState<Organization[]>([])
  async function load() {
    setState({ kind: 'loading' })
    try {
      const next = await listOrganizations(user!.uid)
      setOrganizations(next)
      setState(next.length ? { kind: 'ready', data: { organization: next[0], administrators: [], spaces: [] } } : { kind: 'empty' })
    } catch (error: unknown) {
      const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : ''
      setState(code.includes('permission-denied') ? { kind: 'permission-denied' } : { kind: 'error', message: 'Check your connection and try again.' })
    }
  }
  useEffect(() => { void load() }, [user]) // eslint-disable-line react-hooks/exhaustive-deps
  return <div className="app-page"><header className="topbar"><Brand compact /><div className="topbar-actions"><button className="icon-button" aria-label="Sign out" onClick={() => void signOutUser()}><LogOut size={19} /></button></div></header><main className="selection"><span className="eyebrow">Organization dashboard</span><h1>Choose an organization</h1><p>Select the organization you want to view.</p>{state.kind !== 'ready' ? <StateView state={state} onRetry={state.kind === 'error' ? load : undefined} /> : <div className="org-grid">{organizations.map((org) => <button className="org-option" key={org.id} onClick={() => navigate(`/organizations/${org.id}`, { state: { organization: org } })}><span className="org-avatar"><Building2 /></span><span><strong>{org.name}</strong><small>{org.status === 'active' ? 'Active organization' : 'Suspended organization'}</small></span><ChevronRight /></button>)}</div>}</main></div>
}
