export const sessionToken = value => typeof value === 'string' ? value.replace(/^Bearer\s+/i, '').trim() : ''

// A delayed response may only invalidate the credential used by that request.
export function shouldExpireSession(failed, active, stored) {
  const token = sessionToken(failed)
  return !!token && token === sessionToken(active) && (!sessionToken(stored) || token === sessionToken(stored))
}

export const principalKey = user => user?.userId == null ? '' : `${user.tenantId || ''}:${String(user.userId)}`
