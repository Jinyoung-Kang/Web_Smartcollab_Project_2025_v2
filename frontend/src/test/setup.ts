import '@testing-library/jest-dom/vitest'
import { configure } from '@testing-library/react'

// findBy·waitFor 의 기본 대기(1초)는 CI 러너처럼 느린 환경에서 모자라 테스트가 흔들렸습니다.
// 다른 테스트와 동시에 실행해 재현해 보니 1.4초까지 걸려 3초로 늘립니다(실패할 때만 끝까지 기다림).
configure({ asyncUtilTimeout: 3000 })

// jsdom 은 <dialog> 의 showModal()/close() 를 구현하지 않으므로, 열림 상태만 바꾸는 최소 구현을 둡니다.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
  }
}
