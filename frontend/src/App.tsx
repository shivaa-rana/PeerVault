import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js'
import { loadStripe } from '@stripe/stripe-js'
import { api, jsonBody, type AuthResult, type Page, type PaymentIntent, type Transfer, type User, type Wallet } from './api'

type View = 'home' | 'login' | 'register' | 'dashboard' | 'send' | 'transactions' | 'wallet' | 'fund' | 'profile' | 'admin'
type Reconciliation = { generatedAt: string; walletsChecked: number; completedTransfersChecked: number; discrepancies: string[]; balanced: boolean }
type FraudFlag = { id: string; riskReason: string; severity: string; reviewStatus: string; createdAt: string }

const currencyFormat = (value: string, currency: string) => {
  const [whole = '0', rawFraction = ''] = value.split('.')
  const negative = whole.startsWith('-')
  const integer = BigInt(whole)
  const absoluteInteger = negative ? -integer : integer
  const fraction = rawFraction.replace(/0+$/, '')
  const currencyParts = new Intl.NumberFormat(undefined, {
    style: 'currency', currency, minimumFractionDigits: 0, maximumFractionDigits: 0,
  }).formatToParts(integer)
  const integerIndex = currencyParts.findIndex(part => part.type === 'integer')
  const prefix = currencyParts.slice(0, integerIndex).map(part => part.value).join('')
  const suffix = currencyParts.slice(integerIndex + 1).filter(part => part.type !== 'group')
    .map(part => part.value).join('')
  const groupedInteger = new Intl.NumberFormat(undefined, { useGrouping: true, maximumFractionDigits: 0 })
    .format(absoluteInteger)
  const minimumDigits = new Intl.NumberFormat(undefined, { style: 'currency', currency }).resolvedOptions().minimumFractionDigits ?? 2
  const decimal = fraction || minimumDigits ? new Intl.NumberFormat(undefined, { minimumFractionDigits: 1 })
    .formatToParts(1.1).find(part => part.type === 'decimal')?.value ?? '.' : ''
  const shownFraction = fraction.padEnd(minimumDigits, '0')
  return `${prefix}${groupedInteger}${shownFraction ? decimal + shownFraction : ''}${suffix}`
}

const moneyUnits = (amount: string) => {
  const negative = amount.startsWith('-')
  const [whole = '0', fraction = ''] = (negative ? amount.slice(1) : amount).split('.')
  const units = BigInt(whole) * 10_000n + BigInt(fraction.padEnd(4, '0').slice(0, 4))
  return negative ? -units : units
}

const unitsToMoney = (units: bigint) => {
  const negative = units < 0n
  const absolute = negative ? -units : units
  return `${negative ? '-' : ''}${absolute / 10_000n}.${String(absolute % 10_000n).padStart(4, '0')}`
}

const displayDate = (value: string) => new Date(value).toLocaleString()

function App() {
  const [view, setView] = useState<View>('home')
  const [auth, setAuth] = useState<AuthResult | null>(null)
  const [wallet, setWallet] = useState<Wallet | null>(null)
  const [transfers, setTransfers] = useState<Transfer[]>([])
  const [selected, setSelected] = useState<Transfer | null>(null)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [dark, setDark] = useState(false)
  const [filter, setFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  useEffect(() => {
    if (!auth) return
    let active = true
    Promise.all([
      api<Wallet>('/api/wallet', auth.accessToken),
      api<Page<Transfer>>('/api/transfers?page=0&size=100', auth.accessToken),
    ]).then(([nextWallet, page]) => {
      if (!active) return
      setWallet(nextWallet)
      setTransfers(page.content)
      setError('')
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : 'Unable to load account data')
    })
    return () => { active = false }
  }, [auth, refresh])

  const totals = useMemo(() => {
    const sent = transfers.filter(item => item.senderWalletId === wallet?.id && item.status === 'COMPLETED')
      .reduce((sum, item) => sum + moneyUnits(item.amount), 0n)
    const received = transfers.filter(item => item.recipientWalletId === wallet?.id && item.status === 'COMPLETED')
      .reduce((sum, item) => sum + moneyUnits(item.amount), 0n)
    return { sent, received }
  }, [transfers, wallet])

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true); setError('')
    const form = new FormData(event.currentTarget)
    const payload = Object.fromEntries(form.entries())
    try {
      const path = view === 'register' ? '/api/auth/register' : '/api/auth/login'
      const result = await api<AuthResult>(path, undefined, jsonBody(payload))
      setAuth(result); setView('dashboard'); setNotice('Welcome to PeerVault.')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Authentication failed')
    } finally { setBusy(false) }
  }

  async function submitTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!auth || !wallet) return
    setBusy(true); setError(''); setNotice('')
    const form = new FormData(event.currentTarget)
    const payload = {
      recipientEmail: String(form.get('recipientEmail')).trim(),
      amount: String(form.get('amount')),
      currency: wallet.currency,
    }
    try {
      const result = await api<Transfer>('/api/transfers', auth.accessToken, {
        ...jsonBody(payload),
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      setNotice(result.status === 'COMPLETED'
        ? `Transfer completed: ${currencyFormat(result.amount, result.currency)}`
        : `Transfer not completed: ${result.failureReason ?? result.status}`)
      setView('dashboard'); setRefresh(value => value + 1)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Transfer request failed')
    } finally { setBusy(false) }
  }

  async function openTransfer(id: string) {
    if (!auth) return
    setError('')
    try {
      setSelected(await api<Transfer>(`/api/transfers/${id}`, auth.accessToken))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to load transfer')
    }
  }

  function signOut() {
    setAuth(null); setWallet(null); setTransfers([]); setSelected(null)
    setView('home'); setError(''); setNotice('')
  }

  const body = !auth
    ? view === 'register' || view === 'login'
      ? <AuthForm mode={view} busy={busy} error={error} onSubmit={submitAuth}
          onSwitch={() => { setError(''); setView(view === 'login' ? 'register' : 'login') }} />
      : <Landing onLogin={() => setView('login')} onRegister={() => setView('register')} />
    : (
      <>
        {view === 'dashboard' && <Dashboard user={auth.user} wallet={wallet} transfers={transfers.slice(0, 5)}
          sent={unitsToMoney(totals.sent)} received={unitsToMoney(totals.received)} onSend={() => setView('send')}
          onHistory={() => setView('transactions')} onOpen={openTransfer} onFund={() => setView('fund')} />}
        {view === 'wallet' && <WalletPage wallet={wallet} onFund={() => setView('fund')} />}
        {view === 'send' && <SendForm wallet={wallet} busy={busy} error={error} notice={notice}
          onSubmit={submitTransfer} />}
        {view === 'transactions' && <Transactions transfers={transfers} filter={filter}
          statusFilter={statusFilter} onFilter={setFilter} onStatus={setStatusFilter} onOpen={openTransfer} />}
        {view === 'fund' && <Funding token={auth.accessToken} wallet={wallet}
          onUpdated={() => setRefresh(value => value + 1)} />}
        {view === 'profile' && <Profile user={auth.user} expiresIn={auth.expiresIn} />}
        {view === 'admin' && auth.user.role === 'ADMIN' &&
          <Admin token={auth.accessToken} onError={setError} />}
        {view === 'admin' && auth.user.role !== 'ADMIN' &&
          <section className="card"><h2>Admin access required</h2><p className="subheading">Your account does not have administrator access.</p></section>}
        {selected && <TransferDetail transfer={selected} onClose={() => setSelected(null)} />}
      </>
    )

  return (
    <div className={dark ? 'shell theme-dark' : 'shell'}>
      <header className="topbar">
        <button className="brand text-button" onClick={() => setView(auth ? 'dashboard' : 'home')} aria-label="PeerVault home">
          <span className="brand-mark">P</span><span>PeerVault</span>
        </button>
        <nav className="nav" aria-label="Main navigation">
          {!auth ? <>
            <button onClick={() => setView('login')}>Sign in</button>
            <button className="primary" onClick={() => setView('register')}>Create account</button>
          </> : <>
            <button className={view === 'dashboard' ? 'active' : ''} onClick={() => setView('dashboard')}>Overview</button>
            <button className={view === 'wallet' ? 'active' : ''} onClick={() => setView('wallet')}>Wallet</button>
            <button className={view === 'send' ? 'active' : ''} onClick={() => setView('send')}>Send</button>
            <button className={view === 'transactions' ? 'active' : ''} onClick={() => setView('transactions')}>Activity</button>
            <button className={view === 'fund' ? 'active' : ''} onClick={() => setView('fund')}>Add money</button>
            <button className={view === 'profile' ? 'active' : ''} onClick={() => setView('profile')}>Profile</button>
            {auth.user.role === 'ADMIN' && <button className={view === 'admin' ? 'active' : ''} onClick={() => setView('admin')}>Admin</button>}
            <button className="signout" onClick={signOut}>Sign out</button>
          </>}
          <button className="text-button" onClick={() => setDark(value => !value)} aria-label="Toggle color theme">
            {dark ? 'Light' : 'Dark'}
          </button>
        </nav>
      </header>
      <main className={auth ? 'main' : ''}>
        {error && auth && <div className="form-error" role="alert">{error}</div>}
        {notice && auth && <div className="form-success" role="status">{notice}</div>}
        {body}
      </main>
      <footer className="footer">PeerVault · Moving Money. Maintaining Trust.</footer>
    </div>
  )
}

function Landing({ onLogin, onRegister }: { onLogin: () => void; onRegister: () => void }) {
  return <section className="landing">
    <div className="landing-top">Money, moved with confidence</div>
    <h1>Everyday payments.<br /><em>Thoughtfully secured.</em></h1>
    <p className="landing-copy">A clear, reliable wallet experience built around the things that matter: accountable balances, secure transfers, and a record you can trust.</p>
    <div className="action-row"><button className="primary" onClick={onRegister}>Open your wallet</button><button className="secondary" onClick={onLogin}>Sign in</button></div>
    <div className="landing-bottom"><span>Double-entry accounting</span><span>Protected transfers</span><span>Verified payment funding</span></div>
  </section>
}

function AuthForm({ mode, busy, error, onSubmit, onSwitch }: {
  mode: 'login' | 'register'; busy: boolean; error: string; onSubmit: (event: FormEvent<HTMLFormElement>) => void; onSwitch: () => void
}) {
  const registering = mode === 'register'
  return <form className="card form-card" onSubmit={onSubmit}>
    <div className="eyebrow">PeerVault account</div>
    <h1>{registering ? 'Create your account' : 'Welcome back'}</h1>
    <p className="subheading">{registering ? 'Your wallet starts with a secure account.' : 'Sign in to continue to your wallet.'}</p>
    {registering && <Field label="Full name"><input name="fullName" autoComplete="name" required maxLength={80} /></Field>}
    <Field label="Email"><input name="email" type="email" autoComplete="email" required maxLength={254} /></Field>
    <Field label="Password"><input name="password" type="password" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={registering ? 12 : 1} maxLength={72} /></Field>
    {error && <div className="form-error" role="alert">{error}</div>}
    <button className="primary" disabled={busy} type="submit">{busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}</button>
    <p className="form-footer">{registering ? 'Already have an account?' : 'New to PeerVault?'} <button type="button" onClick={onSwitch}>{registering ? 'Sign in' : 'Create account'}</button></p>
  </form>
}

function Dashboard({ user, wallet, transfers, sent, received, onSend, onFund, onHistory, onOpen }: {
  user: User; wallet: Wallet | null; transfers: Transfer[]; sent: string; received: string;
  onSend: () => void; onFund: () => void; onHistory: () => void; onOpen: (id: string) => void
}) {
  return <>
    <div className="page-heading"><div><div className="eyebrow">Your account</div><h1>Good to see you, {user.fullName.split(' ')[0]}</h1><p className="subheading">Here’s your wallet at a glance.</p></div></div>
    <div className="grid dashboard-grid">
      <section className="card balance-card"><div><div className="eyebrow">Available balance</div><p className="balance">{wallet ? currencyFormat(wallet.availableBalance, wallet.currency) : '—'}</p></div>
        <div className="balance-meta">{wallet ? `${wallet.currency} wallet · ${wallet.status.toLowerCase()}` : 'Loading wallet…'}</div></section>
      <section className="card quick-card"><div><div className="eyebrow">Quick actions</div><h2>Move money simply.</h2><p className="subheading">Send to a PeerVault member or add funds through secure payment verification.</p></div>
        <div className="action-row"><button className="primary" onClick={onSend}>Send money</button><button className="secondary" onClick={onFund}>Add money</button></div></section>
      <Stat label="Money sent" value={wallet ? currencyFormat(sent, wallet.currency) : '—'} />
      <Stat label="Money received" value={wallet ? currencyFormat(received, wallet.currency) : '—'} />
      <Stat label="Wallet status" value={wallet?.status ?? 'Loading'} />
      <section className="card section-card"><div className="section-title"><h2>Recent activity</h2><button className="link-button" onClick={onHistory}>View all</button></div>
        <TransferTable transfers={transfers} walletId={wallet?.id} onOpen={onOpen} /></section>
    </div>
  </>
}

function Stat({ label, value }: { label: string; value: string }) {
  return <section className="card stat-card"><span className="stat-label">{label}</span><strong className="stat-value">{value}</strong></section>
}

function WalletPage({ wallet, onFund }: { wallet: Wallet | null; onFund: () => void }) {
  return <><div className="page-heading"><div><div className="eyebrow">Wallet</div><h1>Your balance</h1><p className="subheading">Balances are updated only after verified ledger postings.</p></div></div>
    <div className="card"><div className="detail-list">
      <div className="detail-item"><span>Available balance</span><strong>{wallet ? currencyFormat(wallet.availableBalance, wallet.currency) : 'Loading…'}</strong></div>
      <div className="detail-item"><span>Currency</span><strong>{wallet?.currency ?? '—'}</strong></div>
      <div className="detail-item"><span>Wallet status</span><strong>{wallet?.status ?? '—'}</strong></div>
      <div className="detail-item"><span>Wallet reference</span><strong>{wallet?.id ?? '—'}</strong></div>
    </div><div className="action-row" style={{ marginTop: 22 }}><button className="primary" onClick={onFund}>Add money</button></div></div>
  </>
}

function SendForm({ wallet, busy, error, notice, onSubmit }: {
  wallet: Wallet | null; busy: boolean; error: string; notice: string; onSubmit: (event: FormEvent<HTMLFormElement>) => void
}) {
  return <form className="card form-card" onSubmit={onSubmit}>
    <div className="eyebrow">Peer-to-peer transfer</div><h1>Send money</h1>
    <p className="subheading">Transfers are posted atomically and can’t exceed your available balance.</p>
    <Field label="Recipient email"><input name="recipientEmail" type="email" required maxLength={254} placeholder="name@example.com" /></Field>
    <Field label={`Amount (${wallet?.currency ?? 'INR'})`}><input name="amount" type="number" min="0.01" step="0.0001" max={wallet?.availableBalance ?? undefined} required /></Field>
    <div className="notice">Available: {wallet ? currencyFormat(wallet.availableBalance, wallet.currency) : 'Loading wallet…'}</div>
    {error && <div className="form-error" role="alert">{error}</div>}
    {notice && <div className="form-success" role="status">{notice}</div>}
    <button className="primary" disabled={busy || !wallet} type="submit" style={{ marginTop: 18 }}>{busy ? 'Processing…' : 'Review and send'}</button>
  </form>
}

function Transactions({ transfers, filter, statusFilter, onFilter, onStatus, onOpen }: {
  transfers: Transfer[]; filter: string; statusFilter: string; onFilter: (value: string) => void;
  onStatus: (value: string) => void; onOpen: (id: string) => void
}) {
  const visible = transfers.filter(item => item.id.toLowerCase().includes(filter.toLowerCase())
    && (statusFilter === 'ALL' || item.status === statusFilter))
  return <><div className="page-heading"><div><div className="eyebrow">Activity</div><h1>Transaction history</h1><p className="subheading">Your latest transfers and their current status.</p></div></div>
    <section className="card"><div className="filters">
      <input className="filter-input" aria-label="Search transaction ID" placeholder="Search transfer ID" value={filter} onChange={event => onFilter(event.target.value)} />
      <select aria-label="Filter by status" value={statusFilter} onChange={event => onStatus(event.target.value)}>
        <option value="ALL">All statuses</option><option>COMPLETED</option><option>FAILED</option><option>PROCESSING</option><option>INITIATED</option>
      </select>
    </div><TransferTable transfers={visible} onOpen={onOpen} /></section>
  </>
}

function TransferTable({ transfers, walletId, onOpen }: { transfers: Transfer[]; walletId?: string; onOpen: (id: string) => void }) {
  if (!transfers.length) return <div className="empty"><strong>No transfers yet</strong>When you send or receive money, it will appear here.</div>
  return <div className="table-wrap"><table><thead><tr><th>Transfer</th><th>Direction</th><th>Amount</th><th>Status</th><th>Date</th></tr></thead>
    <tbody>{transfers.map(item => <tr key={item.id}>
      <td><button className="link-button" onClick={() => onOpen(item.id)}>{item.id.slice(0, 8)}…</button></td>
      <td>{walletId && item.senderWalletId === walletId ? 'Sent' : 'Received'}</td>
      <td className="amount">{currencyFormat(item.amount, item.currency)}</td>
      <td><span className={`status ${item.status}`}>{item.status}</span></td>
      <td>{displayDate(item.createdAt)}</td>
    </tr>)}</tbody></table></div>
}

function TransferDetail({ transfer, onClose }: { transfer: Transfer; onClose: () => void }) {
  return <div className="modal-backdrop" role="presentation" onClick={onClose}>
    <section className="card detail-modal" role="dialog" aria-modal="true" aria-labelledby="detail-title" onClick={event => event.stopPropagation()}>
      <div className="section-title"><h2 id="detail-title">Transfer details</h2><button className="text-button" onClick={onClose}>Close</button></div>
      <div className="detail-list">
        <Detail label="Reference" value={transfer.id} /><Detail label="Status" value={transfer.status} />
        <Detail label="Amount" value={currencyFormat(transfer.amount, transfer.currency)} />
        <Detail label="Created" value={displayDate(transfer.createdAt)} />
        <Detail label="Sender wallet" value={transfer.senderWalletId} /><Detail label="Recipient wallet" value={transfer.recipientWalletId} />
        {transfer.failureReason && <Detail label="Reason" value={transfer.failureReason} />}
      </div>
    </section>
  </div>
}

function Detail({ label, value }: { label: string; value: string }) {
  return <div className="detail-item"><span>{label}</span><strong>{value}</strong></div>
}

function Profile({ user, expiresIn }: { user: User; expiresIn: number }) {
  return <><div className="page-heading"><div><div className="eyebrow">Profile</div><h1>Account settings</h1><p className="subheading">Your identity and security information.</p></div></div>
    <section className="card"><div className="detail-list"><Detail label="Name" value={user.fullName} /><Detail label="Email" value={user.email} />
      <Detail label="Access role" value={user.role} /><Detail label="Access token lifetime" value={`${Math.floor(expiresIn / 60)} minutes`} /></div>
      <p className="notice" style={{ marginTop: 20 }}>Your access token is held in memory for this session and is not written to browser storage.</p>
    </section></>
}

function Funding({ token, wallet, onUpdated }: { token: string; wallet: Wallet | null; onUpdated: () => void }) {
  const [intent, setIntent] = useState<PaymentIntent | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [amount, setAmount] = useState('')
  const [message, setMessage] = useState('')
  const publishableKey = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY as string | undefined
  const stripePromise = useMemo(() => publishableKey ? loadStripe(publishableKey) : null, [publishableKey])

  async function createIntent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true); setError(''); setMessage('')
    try {
      const created = await api<PaymentIntent>('/api/wallet/fund', token, {
        ...jsonBody({ amount, currency: wallet?.currency ?? 'INR' }),
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      setIntent(created)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to create payment request')
    } finally { setBusy(false) }
  }

  return <><div className="page-heading"><div><div className="eyebrow">Verified funding</div><h1>Add money</h1><p className="subheading">Wallet funds are credited only after a verified Stripe webhook.</p></div></div>
    <div className="card form-card">
      {!intent ? <form onSubmit={createIntent}>
        <h2>Start a secure payment</h2><p className="subheading">Current wallet: {wallet?.currency ?? 'INR'}</p>
        {!publishableKey && <p className="notice">Stripe is not configured in this environment. No payment will be created.</p>}
        <Field label="Amount"><input type="number" min="1" step="0.01" required value={amount} onChange={event => setAmount(event.target.value)} /></Field>
        {error && <div className="form-error" role="alert">{error}</div>}
        <button className="primary" type="submit" disabled={busy || !publishableKey}>{busy ? 'Connecting…' : 'Continue to payment'}</button>
      </form> : <>
        <h2>Complete your payment</h2><p className="subheading">{currencyFormat(intent.amount, intent.currency)} · Stripe</p>
        {stripePromise ? <Elements stripe={stripePromise} options={{ clientSecret: intent.clientSecret }}>
          <StripePayment onResult={result => { setMessage(result); onUpdated() }} />
        </Elements> : <div className="form-error">Stripe publishable key is missing; payment confirmation is unavailable.</div>}
        {error && <div className="form-error" role="alert">{error}</div>}
        {message && <div className="form-success" role="status">{message}</div>}
        <button className="text-button" onClick={() => setIntent(null)}>Cancel</button>
      </>}
    </div>
  </>
}

function StripePayment({ onResult }: { onResult: (message: string) => void }) {
  const stripe = useStripe()
  const elements = useElements()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!stripe || !elements) return
    setBusy(true); setError('')
    const result = await stripe.confirmPayment({
      elements,
      confirmParams: { return_url: window.location.href },
      redirect: 'if_required',
    })
    if (result.error) setError(result.error.message ?? 'Stripe could not confirm this payment')
    else onResult('Payment submitted. The wallet will update only after PeerVault verifies the provider webhook.')
    setBusy(false)
  }
  return <form onSubmit={confirm}><PaymentElement />
    {error && <div className="form-error" role="alert">{error}</div>}
    <button className="primary" disabled={!stripe || busy} type="submit" style={{ marginTop: 18 }}>{busy ? 'Confirming…' : 'Pay securely'}</button>
  </form>
}

function Admin({ token, onError }: { token: string; onError: (message: string) => void }) {
  const [report, setReport] = useState<Reconciliation | null>(null)
  const [flags, setFlags] = useState<FraudFlag[]>([])
  const [status, setStatus] = useState('')
  useEffect(() => {
    let active = true
    Promise.all([
      api<Reconciliation>('/api/admin/reconciliation', token),
      api<Page<FraudFlag>>('/api/admin/fraud-flags?page=0&size=50', token),
    ]).then(([result, page]) => {
      if (active) { setReport(result); setFlags(page.content) }
    }).catch((reason: unknown) => { if (active) onError(reason instanceof Error ? reason.message : 'Admin data unavailable') })
    return () => { active = false }
  }, [token, onError])
  async function review(id: string, reviewStatus: string) {
    try {
      await api(`/api/admin/fraud-flags/${id}`, token, { method: 'PATCH', body: JSON.stringify({ status: reviewStatus }) })
      setFlags(current => current.map(flag => flag.id === id ? { ...flag, reviewStatus } : flag))
      setStatus(`Flag ${reviewStatus.toLowerCase()}.`)
    } catch (reason) { onError(reason instanceof Error ? reason.message : 'Unable to update review') }
  }
  return <><div className="page-heading"><div><div className="eyebrow">Administration</div><h1>Operations overview</h1><p className="subheading">Reconciliation results and fraud review queue.</p></div></div>
    <div className="grid dashboard-grid">
      <section className="card stat-card"><span className="stat-label">Wallets checked</span><strong className="stat-value">{report?.walletsChecked ?? '—'}</strong></section>
      <section className="card stat-card"><span className="stat-label">Transfers checked</span><strong className="stat-value">{report?.completedTransfersChecked ?? '—'}</strong></section>
      <section className="card stat-card"><span className="stat-label">Ledger status</span><strong className="stat-value">{report ? report.balanced ? 'Balanced' : 'Review' : '—'}</strong></section>
      {report?.discrepancies.map(item => <section key={item} className="card section-card form-error">{item}</section>)}
      <section className="card section-card"><div className="section-title"><h2>Fraud flags</h2></div>
        {status && <div className="form-success">{status}</div>}
        {flags.length ? <div className="table-wrap"><table><thead><tr><th>Reason</th><th>Severity</th><th>Status</th><th>Created</th><th>Review</th></tr></thead>
          <tbody>{flags.map(flag => <tr key={flag.id}><td>{flag.riskReason}</td><td>{flag.severity}</td><td>{flag.reviewStatus}</td>
            <td>{displayDate(flag.createdAt)}</td><td>{flag.reviewStatus === 'OPEN' && <span className="action-row">
              <button className="secondary" onClick={() => review(flag.id, 'REVIEWED')}>Mark reviewed</button>
              <button className="secondary" onClick={() => review(flag.id, 'DISMISSED')}>Dismiss</button>
            </span>}</td></tr>)}</tbody></table></div> : <div className="empty">No fraud flags require review.</div>}
      </section>
    </div>
  </>
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <div className="field"><label>{label}{children}</label></div>
}

export default App
