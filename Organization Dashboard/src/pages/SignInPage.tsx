import { useState } from 'react'
import { Building2, LogIn, ShieldCheck } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import { Brand } from '../components/Brand'
import { useAuth } from '../auth/AuthContext'

type EntryChoice = 'sign-in' | 'create-organization'

export function SignInPage() {
  const { user, loading, configured, signInWithGoogle, signInWithApple } = useAuth()
  const [choice, setChoice] = useState<EntryChoice | null>(null)
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  if (!loading && user) return <Navigate to="/organizations" replace />
  async function submit(provider: 'google' | 'apple') {
    if (!choice) return
    setError(''); setSubmitting(true)
    try {
      await (provider === 'google' ? signInWithGoogle() : signInWithApple())
      window.location.assign(choice === 'create-organization' ? '/organizations/new' : '/organizations')
    } catch (reason: unknown) {
      const code = typeof reason === 'object' && reason && 'code' in reason ? String(reason.code) : ''
      if (!code.includes('popup-closed-by-user')) setError('We couldn’t complete sign-in. Please try again or use your other linked provider.')
    } finally { setSubmitting(false) }
  }
  return <main className="auth-shell">
    <section className="auth-story"><Brand /><div className="auth-story-copy"><span className="eyebrow light">theSpaces. for organizations</span><h1>All your Spaces. One clear view.</h1><p>See your people, capacity, modules, and organization-owned Spaces—without stepping into anyone’s content.</p></div><p className="privacy-note"><ShieldCheck size={18} /> Your Spaces stay private.</p></section>
    <section className="auth-panel"><div className="auth-card">{choice === null ? <><h2>Welcome</h2><p>Choose what you need to do.</p><div className="entry-choice-actions"><button className="button button--primary" onClick={() => setChoice('sign-in')}><LogIn size={18} /> Sign in</button><button className="button button--secondary" onClick={() => setChoice('create-organization')}><Building2 size={18} /> Create organization</button></div></> : <><button className="auth-back" onClick={() => { setChoice(null); setError('') }}>Back</button><h2>{choice === 'create-organization' ? 'Create your organization' : 'Sign in'}</h2><p>{choice === 'create-organization' ? 'Sign in or create your account to begin Foundation setup.' : 'Use the same account you use in theSpaces.'}</p>{!configured ? <p className="form-error" role="alert">Firebase configuration is missing. Add the required values to .env.local.</p> : <div className="provider-buttons"><button className="button provider-button google-button" disabled={submitting} onClick={() => void submit('google')}><img className="provider-logo" src="/google-logo.svg" alt="" /> Continue with Google</button><button className="button provider-button apple-button" disabled={submitting} onClick={() => void submit('apple')}><picture className="provider-logo" aria-hidden="true"><source srcSet="/apple-logo-white.png" media="(prefers-color-scheme: dark)" /><img src="/apple-logo-black.png" alt="" /></picture> Continue with Apple</button></div>}{error && <p className="form-error" role="alert">{error}</p>}</>}</div></section>
  </main>
}
