/**
 * 이 브라우저 탭의 ID. 요청마다 X-Client-Id 헤더로 보내면 서버가 그 요청이 일으킨 실시간 이벤트에 실어 돌려줍니다.
 * 같은 탭은 자기 변경의 이벤트로 목록을 다시 불러오지 않습니다 — 변경 요청의 응답이 이미 갱신했기 때문입니다 [IMP-03].
 * 사용자 단위가 아니라 탭 단위라, 같은 사람의 다른 탭·기기는 그대로 갱신됩니다.
 */
export const CLIENT_ID: string = typeof crypto !== 'undefined' && 'randomUUID' in crypto
  ? crypto.randomUUID()
  : `tab-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
