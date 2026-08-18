import { initializeApp, type FirebaseApp } from 'firebase/app'
import { getAuth, type Auth } from 'firebase/auth'
import { getFirestore, type Firestore } from 'firebase/firestore'
import { getFunctions, type Functions } from 'firebase/functions'
import { getStorage, type FirebaseStorage } from 'firebase/storage'

const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
}

export const isFirebaseConfigured = Object.values(config).every(Boolean)

let app: FirebaseApp | null = null
export let auth: Auth | null = null
export let db: Firestore | null = null
export let functions: Functions | null = null
export let storage: FirebaseStorage | null = null

if (isFirebaseConfigured) {
  app = initializeApp(config)
  auth = getAuth(app)
  db = getFirestore(app)
  functions = getFunctions(app)
  storage = getStorage(app)
}
