/** 충돌로 버려질 편집본을 파일로 받을 때의 이름: 확장자를 떼고 "(내 편집본).txt" */
export function draftFileName(fileName: string): string {
  return `${fileName.replace(/\.[^.]+$/, '')} (내 편집본).txt`
}

/**
 * 충돌로 버려질 편집본을 보관합니다. 클립보드가 막혀 있으면(권한·비보안 연결) 텍스트 파일로 내려받습니다.
 * 이전에는 복사에 실패해도 "클립보드에 복사해 두었습니다"라고 안내했습니다 [BUG-06].
 */
export async function keepDraft(text: string, fileName: string): Promise<'clipboard' | 'file'> {
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    return 'clipboard'
  } catch {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = draftFileName(fileName)
    link.click()
    URL.revokeObjectURL(url)
    return 'file'
  }
}
