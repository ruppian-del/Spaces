import { useState } from 'react'
import { ShieldCheck } from 'lucide-react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Brand } from '../components/Brand'
import { useAuth } from '../auth/AuthContext'

export function SignInPage() {
  const { user, loading, configured, signInWithGoogle, signInWithApple } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  if (!loading && user) return <Navigate to="/organizations" replace />
  async function submit(provider: 'google' | 'apple') {
    setError(''); setSubmitting(true)
    try { await (provider === 'google' ? signInWithGoogle() : signInWithApple()); navigate('/organizations') }
    catch (error: unknown) {
      const code = typeof error === 'object' && error && 'code' in error ? String(error.code) : ''
      if (!code.includes('popup-closed-by-user')) setError('We couldn’t complete sign-in. Please try again or use your other linked provider.')
    }
    finally { setSubmitting(false) }
  }
  return <main className="auth-shell">
    <section className="auth-story"><Brand /><div className="auth-story-copy"><span className="eyebrow light">theSpaces. for organizations</span><h1>All your Spaces. One clear view.</h1><p>See your people, capacity, modules, and organization-owned Spaces—without stepping into anyone’s content.</p></div><p className="privacy-note"><ShieldCheck size={18} /> Your Spaces stay private.</p></section>
    <section className="auth-panel"><div className="auth-card"><h2>Continue to your Spaces</h2><p>Use the same account you use in theSpaces.</p>
      {!configured ? <p className="form-error" role="alert">Firebase configuration is missing. Add the required values to .env.local.</p> : <div className="provider-buttons"><button className="button provider-button google-button" disabled={submitting} onClick={() => void submit('google')}><img className="provider-logo" src="/google-logo.svg" alt="" /> Continue with Google</button><button className="button provider-button apple-button" disabled={submitting} onClick={() => void submit('apple')}><picture className="provider-logo" aria-hidden="true"><source srcSet="/apple-logo-white.png" media="(prefers-color-scheme: dark)" /><img src="/apple-logo-black.png" alt="" /></picture> Continue with Apple</button></div>}{error && <p className="form-error" role="alert">{error}</p>}
      <p className="auth-help">Space admins and organization administrators can continue.</p></div></section>
  </main>
}
