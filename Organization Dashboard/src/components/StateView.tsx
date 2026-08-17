import { AlertTriangle, LockKeyhole, RefreshCw, SearchX } from 'lucide-react'
import type { LoadState } from '../types'

export function StateView({ state, onRetry }: { state: Exclude<LoadState, { kind: 'ready' }>; onRetry?: () => void }) {
  if (state.kind === 'loading') return <div className="state-card" role="status"><div className="spinner" /><h2>Loading your organization</h2><p>We’re gathering current capacity and access details.</p></div>
  const content = state.kind === 'empty'
    ? { icon: <SearchX />, title: 'No organizations yet', body: 'Your account is not connected to an organization. Ask an organization administrator to add you.' }
    : state.kind === 'permission-denied'
      ? { icon: <LockKeyhole />, title: 'You don’t have access', body: 'This dashboard is available only to organization administrators. Contact your primary administrator if you think this is a mistake.' }
      : { icon: <AlertTriangle />, title: 'We couldn’t load the dashboard', body: state.message }
  return <div className="state-card" role={state.kind === 'error' ? 'alert' : 'status'}>
    <span className="state-icon">{content.icon}</span><h2>{content.title}</h2><p>{content.body}</p>
    {onRetry && <button className="button button--secondary" onClick={onRetry}><RefreshCw size={17} /> Try again</button>}
  </div>
}

