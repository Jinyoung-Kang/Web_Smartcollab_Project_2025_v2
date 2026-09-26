import '@testing-library/jest-dom/vitest'

// jsdom 은 <dialog> 의 showModal()/close() 를 구현하지 않으므로, 열림 상태만 바꾸는 최소 구현을 둡니다.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
  }
}
