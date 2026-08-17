import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { GoogleAuthProvider, OAuthProvider, onAuthStateChanged, signInWithPopup, signOut, type User } from 'firebase/auth'
import { auth, isFirebaseConfigured } from '../data/firebase'

interface AuthValue {
  user: User | null
  loading: boolean
  configured: boolean
  signInWithGoogle(): Promise<void>
  signInWithApple(): Promise<void>
  signOutUser(): Promise<void>
}

const AuthContext = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(isFirebaseConfigured)

  useEffect(() => {
    if (!auth) return
    return onAuthStateChanged(auth, (next) => { setUser(next); setLoading(false) })
  }, [])

  return <AuthContext.Provider value={{
    user, loading, configured: isFirebaseConfigured,
    signInWithGoogle: async () => {
      if (!auth) throw new Error('Firebase is not configured.')
      await signInWithPopup(auth, new GoogleAuthProvider())
    },
    signInWithApple: async () => {
      if (!auth) throw new Error('Firebase is not configured.')
      const provider = new OAuthProvider('apple.com')
      provider.addScope('email'); provider.addScope('name')
      await signInWithPopup(auth, provider)
    },
    signOutUser: async () => { if (auth) await signOut(auth) },
  }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
