/**
 * 동시 실행 개수를 제한하는 작업 대기열 (React 와 무관한 순수 로직 — 단위 테스트 가능).
 */
export interface QueueTask<T> {
  id: number
  payload: T
}

export function createTaskQueue<T>(concurrency: number, run: (task: QueueTask<T>) => Promise<void>) {
  const waiting: QueueTask<T>[] = []
  let running = 0

  const pump = () => {
    while (running < concurrency && waiting.length > 0) {
      const task = waiting.shift()!
      running++
      run(task)
        .catch(() => undefined)
        .finally(() => {
          running--
          pump()
        })
    }
  }

  return {
    push(task: QueueTask<T>) {
      waiting.push(task)
      pump()
    },
    /** 아직 시작하지 않은 작업을 대기열에서 뺍니다. 뺐으면 true. */
    cancel(id: number): boolean {
      const index = waiting.findIndex((t) => t.id === id)
      if (index < 0) return false
      waiting.splice(index, 1)
      return true
    },
    get running() {
      return running
    },
    get waiting() {
      return waiting.length
    },
  }
}
