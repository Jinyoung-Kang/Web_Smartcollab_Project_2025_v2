# 파일 처리 점검 (2026-10-03T08:02:02.901Z)

## 업로드 파일명
| name | upload | stored | disposition | note |
|---|---|---|---|---|
| "a\"b.txt" | 201 | "a%22b.txt" | attachment; filename="a%22b.txt"; filename*=UTF-8''a%2522b.txt |  |
| "it's.txt" | 201 | "it's.txt" | attachment; filename="it's.txt"; filename*=UTF-8''it%27s.txt |  |
| "semi;colon.txt" | 201 | "semi;colon.txt" | attachment; filename="semi;colon.txt"; filename*=UTF-8''semi%3Bcolon.txt |  |
| "x\r\ny.txt" | 201 | "x%0D%0Ay.txt" | attachment; filename="x%0D%0Ay.txt"; filename*=UTF-8''x%250D%250Ay.txt |  |
| "x\ny.txt" | 201 | "x%0Ay.txt" | attachment; filename="x%0Ay.txt"; filename*=UTF-8''x%250Ay.txt |  |
| "../../escape.txt" | 201 | "escape.txt" | attachment; filename="escape.txt"; filename*=UTF-8''escape.txt |  |
| "..\\..\\escape.txt" | 201 | "escape.txt" | attachment; filename="escape.txt"; filename*=UTF-8''escape.txt |  |
| "dir/inner.txt" | 201 | "inner.txt" | attachment; filename="inner.txt"; filename*=UTF-8''inner.txt |  |
| "‮txt.exe" | 201 | "txt.exe" | attachment; filename="txt.exe"; filename*=UTF-8''txt.exe |  |
| "CON.txt" | 201 | "CON.txt" | attachment; filename="CON.txt"; filename*=UTF-8''CON.txt |  |
| ".htaccess" | 201 | ".htaccess" | attachment; filename=".htaccess"; filename*=UTF-8''.htaccess |  |
| " " | 400 | 이름을 입력하세요. |  |  |
| "." | 400 | 사용할 수 없는 이름입니다. |  |  |
| ".." | 400 | 사용할 수 없는 이름입니다. |  |  |
| "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | 400 | 이름은 255자 이하여야 합니다. |  |  |
| "한글 파일 이름.txt" | 201 | "한글 파일 이름.txt" | attachment; filename="______ _____ _____.txt"; filename*=UTF-8''%ED%95%9C%EA%B8% |  |
| "😀.txt" | 201 | "😀.txt" | attachment; filename="_.txt"; filename*=UTF-8''%F0%9F%98%80.txt |  |
| "file%20name%0d%0a.txt" | 201 | "file%20name%0d%0a.txt" | attachment; filename="file%20name%0d%0a.txt"; filename*=UTF-8''file%2520name%250 |  |
| "name\u0000.txt" | 500 | 서버 내부 오류가 발생했습니다. |  |  |
| "<img src=x onerror=alert(1)>.txt" | 201 | "<img src=x onerror=alert(1)>.txt" | attachment; filename="<img src=x onerror=alert(1)>.txt"; filename*=UTF-8''%3Cimg |  |

## 이름 바꾸기 (파일·폴더)
| name | status | result | folder |
|---|---|---|---|
| "a\"b.txt" | 204 | "" | 204 |
| "it's.txt" | 204 | "" | 204 |
| "semi;colon.txt" | 204 | "" | 204 |
| "x\r\ny.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "x\ny.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "../../escape.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "..\\..\\escape.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "dir/inner.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "‮txt.exe" | 400 | 이름에 글자 방향 제어 문자나 보이지 않는 문자를 쓸 수 없습니다. | 400 |
| "CON.txt" | 204 | "" | 204 |
| ".htaccess" | 204 | "" | 204 |
| " " | 400 | 새 이름을 입력하세요. | 400 |
| "." | 400 | 사용할 수 없는 이름입니다. | 400 |
| ".." | 400 | 사용할 수 없는 이름입니다. | 400 |
| "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | 400 | 이름은 255자 이하입니다. | 400 |
| "한글 파일 이름.txt" | 204 | "" | 204 |
| "😀.txt" | 204 | "" | 204 |
| "file%20name%0d%0a.txt" | 204 | "" | 204 |
| "name\u0000.txt" | 400 | 이름에 / \ 또는 제어 문자를 쓸 수 없습니다. | 400 |
| "<img src=x onerror=alert(1)>.txt" | 204 | "" | 204 |

## 미리보기·다운로드 응답 헤더
| file | kind | status | type | disposition | nosniff | csp |
|---|---|---|---|---|---|---|
| evil.svg | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.svg | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.html | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.html | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.htm | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.htm | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.xhtml | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.xhtml | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.txt | view | 200 | text/plain;charset=UTF-8 | inline | nosniff | sandbox |
| evil.txt | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.png | view | 200 | image/png | inline | nosniff | sandbox |
| evil.png | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.pdf | view | 200 | application/pdf | inline | nosniff | csp(샌드박스 없음) |
| evil.pdf | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.js | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.js | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil.md | view | 200 | text/plain;charset=UTF-8 | inline | nosniff | sandbox |
| evil.md | download | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil | view | 200 | application/octet-stream | attachment | nosniff | sandbox |
| evil | download | 200 | application/octet-stream | attachment | nosniff | sandbox |

## 공유 다운로드 헤더
```
{
  "upload": 201,
  "status": 200,
  "disposition": "attachment; filename=\"x%22%0D%0ASet-Cookie: a=1.txt\"; filename*=UTF-8''x%2522%250D%250ASet-Cookie%3A%20a%3D1.txt",
  "setCookie": "XSRF-TOKEN=…; Path=/"
}
```
