declare module 'jschardet' {
  export interface DetectResult {
    encoding: string | null
    confidence: number
  }
  export function detect(buffer: Uint8Array | string): DetectResult
}

declare module 'virtual:pwa-register' {
  export function registerSW(options?: {
    immediate?: boolean
    onNeedRefresh?: () => void
    onOfflineReady?: () => void
    onRegistered?: (registration: ServiceWorkerRegistration | undefined) => void
    onRegisterError?: (error: unknown) => void
  }): (reloadPage?: boolean) => Promise<void>
}
