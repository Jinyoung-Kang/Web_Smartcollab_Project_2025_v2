/** 폴더 화면 주소 — 팀 스토리지의 폴더는 /teams/{팀}/folders/{폴더}, 내 드라이브의 폴더는 /drive/{폴더} */
export function folderPath(folderId: number, teamId?: number | null): string {
  return teamId ? `/teams/${teamId}/folders/${folderId}` : `/drive/${folderId}`
}
