/**
 * 목적격 조사 [UX-04]. 마지막 글자가 한글이면 받침이 있으면 "을", 없으면 "를"을 고릅니다.
 * 한글로 끝나지 않으면(파일 확장자·영문 이름 등) 읽는 법을 알 수 없어 "을(를)"을 씁니다.
 */
export function objectParticle(word: string): string {
  const code = word.length > 0 ? word.charCodeAt(word.length - 1) : 0
  if (code < 0xac00 || code > 0xd7a3) return '을(를)'
  return (code - 0xac00) % 28 === 0 ? '를' : '을'
}
