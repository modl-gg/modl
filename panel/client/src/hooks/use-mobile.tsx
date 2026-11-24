import { useSyncExternalStore } from "react"

const MOBILE_BREAKPOINT = 768
const MOBILE_QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`

function subscribeToViewport(onChange: () => void): () => void {
  const mql = window.matchMedia(MOBILE_QUERY)
  mql.addEventListener("change", onChange)
  return () => { mql.removeEventListener("change", onChange); }
}

function isMobileViewport(): boolean {
  return window.innerWidth < MOBILE_BREAKPOINT
}

function isMobileOnServer(): boolean {
  return false
}

export function useIsMobile(): boolean {
  return useSyncExternalStore(subscribeToViewport, isMobileViewport, isMobileOnServer)
}
