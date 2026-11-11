const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export type User = {
  id: string
  email: string
  fullName: string
  role: 'USER' | 'ADMIN'
}

export type AuthResult = {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

export type Wallet = {
  id: string
  availableBalance: string
  currency: string
  status: string
}

export type Transfer = {
  id: string
  senderWalletId: string
  recipientWalletId: string
  amount: string
  currency: string
  status: string
  failureReason?: string
  createdAt: string
}

export type Page<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export type PaymentIntent = {
  provider: string
  providerIntentId: string
  clientSecret: string
  amount: string
  currency: string
  status: string
}

export async function api<T>(
  path: string,
  token?: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  if (!response.ok) {
    const body = await response.json().catch(() => undefined) as { error?: string; detail?: string } | undefined
    throw new Error(body?.detail ?? body?.error ?? `Request failed (${response.status})`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const jsonBody = (value: unknown) => ({
  method: 'POST',
  body: JSON.stringify(value),
})
