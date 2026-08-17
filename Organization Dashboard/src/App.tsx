import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { StateView } from './components/StateView'
import { SignInPage } from './pages/SignInPage'
import { OrganizationSelectPage } from './pages/OrganizationSelectPage'
import { DashboardPage } from './pages/DashboardPage'

function Protected({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <main className="centered-state"><StateView state={{ kind: 'loading' }} /></main>
  return user ? children : <Navigate to="/sign-in" replace />
}

export default function App() {
  return <AuthProvider><BrowserRouter><Routes>
    <Route path="/sign-in" element={<SignInPage />} />
    <Route path="/organizations" element={<Protected><OrganizationSelectPage /></Protected>} />
    <Route path="/organizations/:organizationId" element={<Protected><DashboardPage /></Protected>} />
    <Route path="*" element={<Navigate to="/sign-in" replace />} />
  </Routes></BrowserRouter></AuthProvider>
}
