import { reactive } from 'vue'
import type { OperationAccepted, OperationResult, OperationStatus } from './types'
import { parseOperationStatus } from './validation'

export type WebSocketLike = Pick<WebSocket, 'close'> & { onopen: (() => void) | null; onmessage: ((event: MessageEvent) => void) | null; onerror: (() => void) | null; onclose: (() => void) | null }
export type WebSocketFactory = (url: string) => WebSocketLike

export const operationProgressPercent = (status: OperationStatus | null): number | null => {
  const libraryProgress = status?.libraryRefreshProgress
  if (libraryProgress) {
    const pageFraction = libraryProgress.activeSourceCompletedPages === null || libraryProgress.activeSourceTotalPages === null
      ? 0 : libraryProgress.activeSourceCompletedPages / libraryProgress.activeSourceTotalPages
    return Math.min(100, Math.round((libraryProgress.completedSources + pageFraction) / libraryProgress.totalSources * 100))
  }
  return status?.totalSteps === null || !status ? null : Math.min(100, Math.round(status.completedSteps / Math.max(1, status.totalSteps) * 100))
}

export const operationProgressLabel = (status: OperationStatus | null): string => {
  const percent = operationProgressPercent(status)
  const libraryProgress = status?.libraryRefreshProgress
  if (libraryProgress && percent !== null) {
    const pages = libraryProgress.activeSourceCompletedPages === null || libraryProgress.activeSourceTotalPages === null
      ? '' : ` · ${libraryProgress.activeSourceCompletedPages}/${libraryProgress.activeSourceTotalPages} PAGES`
    return `${percent}% · ${libraryProgress.completedSources}/${libraryProgress.totalSources} SOURCES${pages}`
  }
  if (!status || percent === null || status.totalSteps === null) return status?.action || 'WORKING'
  return `${percent}% · ${status.completedSteps}/${status.totalSteps} PAGES`
}

export class OperationFailureError extends Error {
  constructor(readonly code: string, message: string) { super(message); this.name = 'OperationFailureError' }
}

export class OperationProgressController {
  readonly state = reactive<{ active: OperationStatus | null; connectionError: string | null }>({ active: null, connectionError: null })
  private socket: WebSocketLike | null = null
  private pending: { resolve: (result: OperationResult) => void; reject: (error: unknown) => void } | null = null

  constructor(private readonly factory: WebSocketFactory = url => new WebSocket(url) as unknown as WebSocketLike) {}

  track(accepted: OperationAccepted): Promise<OperationResult> {
    if (this.pending) return Promise.reject(new Error('An operation is already being tracked'))
    this.state.connectionError = null
    this.state.active = { operationId: accepted.operationId, kind: accepted.kind, phase: 'queued', action: 'Waiting to start', completedSteps: 0, totalSteps: null, result: null, error: null, libraryRefreshProgress: null, bulkRepublishProgress: null }
    return new Promise<OperationResult>((resolve, reject) => {
      this.pending = { resolve, reject }
      const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      this.socket = this.factory(`${scheme}//${window.location.host}/api/v1/operations/${encodeURIComponent(accepted.operationId)}/events`)
      this.socket.onmessage = event => this.accept(event.data)
      this.socket.onerror = () => this.failConnection()
      this.socket.onclose = () => { if (this.pending) this.failConnection() }
    })
  }

  dispose(): void {
    if (this.pending) this.pending.reject(new OperationFailureError('progress_connection_lost', 'Progress connection closed. Please reload.'))
    this.pending = null
    this.socket?.close()
    this.socket = null
    this.state.active = null
  }

  private accept(raw: unknown): void {
    try {
      const status = parseOperationStatus(typeof raw === 'string' ? JSON.parse(raw) : raw)
      this.state.active = status
      if (status.phase === 'succeeded' && status.result) this.finish(() => this.pending?.resolve(status.result as OperationResult))
      else if (status.phase === 'failed' && status.error) this.finish(() => this.pending?.reject(new OperationFailureError(status.error!.code, status.error!.message)))
    } catch { this.failInvalidProgress() }
  }

  private finish(callback: () => void): void { callback(); this.pending = null; this.socket?.close(); this.socket = null }
  private failConnection(): void {
    if (!this.pending) return
    const error = new OperationFailureError('progress_connection_lost', 'Progress connection lost. Please reload.')
    this.fail(error)
  }

  private failInvalidProgress(): void {
    if (!this.pending) return
    this.fail(new OperationFailureError('invalid_progress_update', 'Progress update was invalid. Please reload.'))
  }

  private fail(error: OperationFailureError): void {
    this.state.connectionError = error.message
    this.state.active = null
    this.pending?.reject(error)
    this.pending = null
    this.socket?.close()
    this.socket = null
  }
}
