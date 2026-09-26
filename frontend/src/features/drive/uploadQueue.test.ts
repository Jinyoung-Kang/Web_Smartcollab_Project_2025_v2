import { createTaskQueue } from './uploadQueue'

describe('createTaskQueue', () => {
  it('동시에 최대 N개만 실행하고, 끝나면 다음 작업을 이어서 실행한다', async () => {
    let active = 0
    let peak = 0
    const done: number[] = []
    const resolvers: Array<() => void> = []
    const queue = createTaskQueue<number>(3, (task) => {
      active++
      peak = Math.max(peak, active)
      return new Promise<void>((resolve) => {
        resolvers.push(() => {
          active--
          done.push(task.id)
          resolve()
        })
      })
    })
    for (let i = 1; i <= 7; i++) queue.push({ id: i, payload: i })
    expect(queue.running).toBe(3)
    expect(queue.waiting).toBe(4)
    while (resolvers.length) {
      resolvers.shift()!()
      await Promise.resolve()
      await Promise.resolve()
    }
    expect(peak).toBe(3)
    expect(done).toEqual([1, 2, 3, 4, 5, 6, 7])
  })

  it('실패한 작업이 있어도 대기열은 계속 진행된다', async () => {
    const ran: number[] = []
    const queue = createTaskQueue<number>(1, async (task) => {
      ran.push(task.id)
      if (task.id === 1) throw new Error('boom')
    })
    queue.push({ id: 1, payload: 1 })
    queue.push({ id: 2, payload: 2 })
    await new Promise((r) => setTimeout(r, 0))
    expect(ran).toEqual([1, 2])
  })

  it('[BUG-03] 대기 중인 작업을 취소하면 실행하지 않는다', async () => {
    const ran: number[] = []
    const queue = createTaskQueue<number>(1, async (task) => {
      ran.push(task.id)
    })
    queue.push({ id: 1, payload: 1 })
    queue.push({ id: 2, payload: 2 })
    queue.push({ id: 3, payload: 3 })
    expect(queue.cancel(2)).toBe(true)
    expect(queue.cancel(99)).toBe(false)
    await new Promise((r) => setTimeout(r, 0))
    expect(ran).toEqual([1, 3])
  })
})
