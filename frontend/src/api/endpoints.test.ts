import { shareApi } from './endpoints'

describe('shareApi', () => {
  it('[L-8] 주소창에서 온 공유 토큰을 경로 한 조각으로 인코딩한다 — 그대로 넣으면 "../" 로 다른 API 를 부를 수 있음', () => {
    expect(shareApi.downloadUrl('../../files/1')).toBe('/api/public/shares/..%2F..%2Ffiles%2F1/download')
  })
})
