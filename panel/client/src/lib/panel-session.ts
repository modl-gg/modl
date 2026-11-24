const SESSION_KEY_HEADER = 'X-Modl-Session-Key';
const SESSION_KEY_STORAGE_KEY = 'modl.sessionKey';
const PANEL_API_PREFIX = '/v1/panel/';

type SessionEffect = 'issues' | 'ends';

const RESTRICTION_CODES: ReadonlySet<string> = new Set(['SECOND_FACTOR_ENROLLMENT_REQUIRED', 'SECOND_FACTOR_REQUIRED']);
type SessionKeyListener = (sessionKey: string | null) => void;

const SESSION_EFFECTS: Readonly<Record<string, SessionEffect>> = {
  'POST /v1/public/setup/claim': 'issues',
  'POST /v1/public/setup/sign-in': 'issues',
  'POST /v1/public/staff/invitations/accept': 'issues',
  'POST /v1/panel/auth/verify-email-code': 'issues',
  'POST /v1/panel/auth/link': 'issues',
  'POST /v1/panel/auth/webauthn/login/verify': 'issues',
  'POST /v1/panel/auth/password/login': 'issues',
  'POST /v1/panel/auth/sign-in/second-factor': 'issues',
  'PATCH /v1/panel/auth/email': 'issues',
  'POST /v1/panel/auth/logout': 'ends',
  'DELETE /v1/panel/auth/sessions': 'ends',
};

const listeners = new Set<SessionKeyListener>();
let memorySessionKey: string | null = null;

function sessionStore(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readSessionKey(): string | null {
  const store = sessionStore();
  if (!store) return memorySessionKey;
  try {
    return store.getItem(SESSION_KEY_STORAGE_KEY) || null;
  } catch {
    return memorySessionKey;
  }
}

function writeSessionKey(sessionKey: string | null): void {
  memorySessionKey = sessionKey;
  try {
    if (sessionKey) {
      sessionStore()?.setItem(SESSION_KEY_STORAGE_KEY, sessionKey);
    } else {
      sessionStore()?.removeItem(SESSION_KEY_STORAGE_KEY);
    }
  } catch {
    return;
  }
}

function notify(sessionKey: string | null): void {
  listeners.forEach((listener) => listener(sessionKey));
}

function endSession(): void {
  writeSessionKey(null);
  notify(null);
}

export function subscribeToSessionKeyChanges(listener: SessionKeyListener): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key !== SESSION_KEY_STORAGE_KEY && event.key !== null) return;
    memorySessionKey = event.newValue;
    listener(readSessionKey());
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

function pathOnly(path: string): string {
  const queryStart = path.search(/[?#]/);
  return queryStart === -1 ? path : path.slice(0, queryStart);
}

function sessionEffectOf(method: string, path: string): SessionEffect | undefined {
  return SESSION_EFFECTS[`${method.toUpperCase()} ${pathOnly(path)}`];
}

export function attachSessionKey(path: string, headers: Headers): string | null {
  if (!path.startsWith(PANEL_API_PREFIX)) return null;
  const sessionKey = readSessionKey();
  if (sessionKey) headers.set(SESSION_KEY_HEADER, sessionKey);
  return sessionKey;
}

interface IssuingResponseBody {
  sessionKey: string | null;
  awaitsSecondFactor: boolean;
}

async function readIssuingResponseBody(response: Response): Promise<IssuingResponseBody> {
  const body: unknown = await response.clone().json().catch(() => null);
  if (typeof body !== 'object' || body === null) return { sessionKey: null, awaitsSecondFactor: false };
  const sessionKey = 'sessionKey' in body && typeof body.sessionKey === 'string' && body.sessionKey.length > 0 ? body.sessionKey : null;
  const awaitsSecondFactor = 'secondFactor' in body && typeof body.secondFactor === 'object' && body.secondFactor !== null;
  return { sessionKey, awaitsSecondFactor };
}

async function restrictsCurrentSession(response: Response): Promise<boolean> {
  if (response.status !== 403) return false;
  const body: unknown = await response.clone().json().catch(() => null);
  return typeof body === 'object' && body !== null && 'code' in body
    && typeof body.code === 'string' && RESTRICTION_CODES.has(body.code);
}

export async function observeSessionResponse(
  method: string,
  path: string,
  response: Response,
  sentSessionKey: string | null,
): Promise<void> {
  const effect = sessionEffectOf(method, path);

  if (response.ok && effect === 'issues') {
    const { sessionKey, awaitsSecondFactor } = await readIssuingResponseBody(response);
    if (sessionKey !== null || !awaitsSecondFactor) writeSessionKey(sessionKey);
    return;
  }

  if (response.ok && effect === 'ends') {
    endSession();
    return;
  }

  const rejectedCurrentKey = sentSessionKey !== null && readSessionKey() === sentSessionKey;
  if (response.status === 401 && effect !== 'issues' && rejectedCurrentKey) {
    endSession();
    return;
  }

  if (rejectedCurrentKey && await restrictsCurrentSession(response)) {
    notify(sentSessionKey);
  }
}
