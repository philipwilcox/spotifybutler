import { describe, expect, it } from 'vitest'
import { operationProgressLabel, operationProgressPercent, OperationProgressController } from './operation-progress'
import type { OperationStatus } from './types'

class FakeSocket {
  onopen: (() => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null
  onclose: (() => void) | null = null
  close(): void {}
}

const status = (overrides: Record<string, unknown>) => JSON.stringify({
  operationId: 'op-1', kind: 'library_refresh', phase: 'running', action: 'Refreshing library source',
  completedSteps: 12, totalSteps: null, result: null, error: null, ...overrides,
})

describe('OperationProgressController', () => {
  it('formats fractional aggregate source progress and terminal completion', () => {
    const aggregate: OperationStatus = {
      operationId: 'op-1', kind: 'library_refresh', phase: 'running', action: 'Refreshing library source',
      completedSteps: 12, totalSteps: null, result: null, error: null,
      libraryRefreshProgress: { completedSources: 8, totalSources: 35, activeSourceCompletedPages: 4, activeSourceTotalPages: 7 },
    }
    expect(operationProgressPercent(aggregate)).toBe(24)
    expect(operationProgressLabel(aggregate)).toBe('24% · 8/35 SOURCES · 4/7 PAGES')
    expect(operationProgressPercent({ ...aggregate, phase: 'succeeded', libraryRefreshProgress: { completedSources: 35, totalSources: 35, activeSourceCompletedPages: null, activeSourceTotalPages: null } })).toBe(100)
    expect(operationProgressLabel({ ...aggregate, totalSteps: 7, completedSteps: 4, libraryRefreshProgress: null })).toBe('57% · 4/7 PAGES')
  })

  it('accepts both single-source page progress and aggregate full-refresh progress', () => {
    const socket = new FakeSocket()
    const controller = new OperationProgressController(() => socket)
    void controller.track({ operationId: 'op-1', kind: 'library_refresh' }).catch(() => undefined)

    socket.onmessage?.({ data: status({ completedSteps: 4, totalSteps: 7 }) } as MessageEvent)
    expect(controller.state.active).toMatchObject({ completedSteps: 4, totalSteps: 7, libraryRefreshProgress: null })

    socket.onmessage?.({ data: status({
      libraryRefreshProgress: {
        completedSources: 8,
        totalSources: 35,
        activeSourceCompletedPages: 4,
        activeSourceTotalPages: 7,
      },
    }) } as MessageEvent)
    expect(controller.state.active?.libraryRefreshProgress).toEqual({
      completedSources: 8,
      totalSources: 35,
      activeSourceCompletedPages: 4,
      activeSourceTotalPages: 7,
    })
  })

  it('allows publish planning to discover a total after starting indeterminate', async () => {
    const socket = new FakeSocket()
    const controller = new OperationProgressController(() => socket)
    const progress = controller.track({ operationId: 'op-1', kind: 'publish_plan' })

    expect(controller.state.active?.totalSteps).toBeNull()
    socket.onmessage?.({ data: status({ kind: 'publish_plan', action: 'Spotify page estimate available', completedSteps: 0, totalSteps: 1 }) } as MessageEvent)
    expect(controller.state.active).toMatchObject({ kind: 'publish_plan', completedSteps: 0, totalSteps: 1 })
    socket.onmessage?.({ data: status({
      kind: 'publish_plan', phase: 'succeeded', action: 'Completed', completedSteps: 1, totalSteps: 1,
      result: { type: 'publish_plan', plan: { definitionId: 'definition', playlistName: 'Playlist', action: 'create', candidates: [], message: null, publishFlowId: 'flow-1' } },
    }) } as MessageEvent)

    await expect(progress).resolves.toMatchObject({ type: 'publish_plan' })
    expect(controller.state.connectionError).toBeNull()
  })

  it('initializes every operation with an unknown total', () => {
    for (const kind of ['library_refresh', 'library_playlist_publish', 'publish_plan', 'publish_create', 'publish_adopt', 'destination_sync'] as const) {
      const controller = new OperationProgressController(() => new FakeSocket())
      void controller.track({ operationId: `op-${kind}`, kind }).catch(() => undefined)
      expect(controller.state.active?.totalSteps).toBeNull()
      controller.dispose()
    }
  })

  it('reports invalid progress data without claiming the connection was lost', () => {
    const socket = new FakeSocket()
    const controller = new OperationProgressController(() => socket)
    void controller.track({ operationId: 'op-1', kind: 'library_refresh' }).catch(() => undefined)

    socket.onmessage?.({ data: status({ libraryRefreshProgress: { completedSources: 9, totalSources: 8 } }) } as MessageEvent)
    expect(controller.state.connectionError).toBe('Progress update was invalid. Please reload.')
    expect(controller.state.active).toBeNull()
  })

  it('releases the busy operation when completed steps exceed the advertised total', async () => {
    const socket = new FakeSocket()
    const controller = new OperationProgressController(() => socket)
    const tracked = controller.track({ operationId: 'op-1', kind: 'library_playlist_publish' })

    socket.onmessage?.({ data: status({ kind: 'library_playlist_publish', completedSteps: 1, totalSteps: 1 }) } as MessageEvent)
    expect(controller.state.active).toMatchObject({ completedSteps: 1, totalSteps: 1 })
    socket.onmessage?.({ data: status({ kind: 'library_playlist_publish', completedSteps: 2, totalSteps: 1 }) } as MessageEvent)

    await expect(tracked).rejects.toMatchObject({ code: 'invalid_progress_update' })
    expect(controller.state.active).toBeNull()
  })
})
