# ADR-0013 공개 데모는 로그인 앞에 접근 코드 잠금을 둠 — 앱 안의 필터로, 다른 포트폴리오 서비스의 잠금과 같은 방식

## 배경
Azure(App Service · Database for MySQL · Blob) 구성으로 포트폴리오 데모를 공개하려면, 가입 제한(IP 당 시간당 5회)·저장 한도·AI 토큰 한도는
있지만, 주소를 아는 누구나 가입해 파일을 올리고 AI 요약·번역(Anthropic 과금)을 부를 수 있습니다. 체험 계정 비밀번호도 공개 설정 API 로 안내됩니다.
다른 포트폴리오 서비스(AWS)는 앞단 프록시(Caddy) 뒤의 잠금화면(gate)으로 접근 코드를 받은 사람만 들이고 있습니다.

## 선택지
1. 위치: **앱 안의 서블릿 필터** / App Service 인증(Entra ID 로그인) / 사이드카 프록시 — App Service 는 컨테이너 앞에 우리 프록시를 둘 자리가 없고,
   Entra 로그인은 채용 담당자에게 Microsoft 계정을 요구합니다.
2. 코드 저장: 원문 / **scrypt 해시(이름 붙인 여러 개)** — 다른 서비스 gate 와 같은 형식이라 같은 명령으로 만든 해시를 그대로 씁니다.
3. scrypt 구현: BouncyCastle(약 8MB) 의존성 / **RFC 7914 를 직접 구현하고 RFC 시험 벡터·Python `hashlib.scrypt` 결과로 확인**
4. 운영 시간: AWS 처럼 평일 09–18시만 켜기 / **상시** — App Service 요금제는 앱을 멈춰도 같은 요금이고 MySQL B1ms 는 12개월 무료 범위라 끄는 이득이 없습니다.

## 결정
- `AccessGateFilter` 를 추적 ID 필터 바로 뒤, 본문 제한·스프링 시큐리티보다 먼저 둡니다. `GATE_CODES` 가 비어 있으면 아무것도 하지 않습니다(개발·시험·E2E 기본값).
- 잠금을 풀지 않은 요청: 화면 이동(GET·HEAD, `Accept: text/html`, WebSocket 아님)은 잠금화면(401), 그 밖은 401 problem+json `GATE_LOCKED`.
  `/actuator/health/**` 는 열어 둡니다(App Service 헬스 체크). 프론트는 API 가 `GATE_LOCKED` 를 받으면 로그아웃 대신 잠금화면으로 이동합니다.
- 맞히면 `__Host-sc_gate` 쿠키(HttpOnly·Secure·SameSite=Lax, 12시간, 코드 이름·만료를 HMAC 서명)를 줍니다. 목록에서 지운 코드의 쿠키는 바로 무효입니다.
- 틀린 시도는 IP 당 15분에 5번이면 15분, 전체 1시간에 30번이면 30분 잠급니다(다른 서비스 gate 와 같은 값). 해시 계산(회당 16MB)은 동시에 2개까지.
- 잠금 해제 POST 는 Origin 이 Host 와 같아야 받습니다(`null` 이면 Sec-Fetch-Site same-origin 일 때만). 다음 주소는 같은 사이트 경로만.
- 잠금화면은 인라인 스크립트·스타일 없이 `/__gate/gate.css` 를 받고, CSP `default-src 'none'; style-src 'self'; form-action 'self'`.

## 결과
- 코드를 모르는 방문자는 가입·업로드·AI 호출은 물론 앱의 JS 도 받지 못합니다. 대신 외부 공유 링크(`/s/…`)도 코드를 아는 사람만 열 수 있습니다.
- 권한 판단(AccessPolicy)과는 별개인 배포 단위의 한 겹입니다. 계정·권한 규칙은 그대로입니다.
- 시도 제한과 쿠키 검증 상태가 인메모리라 인스턴스 1개 전제입니다(다른 요청 제한과 같음).
- 근거: `ScryptTest`(RFC 벡터·Python 해시 호환)·`AccessGateFilterTest`·`AccessGateIntegrationTest`(실제 Tomcat 에서 보안 필터보다 먼저).
