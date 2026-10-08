# CONTENT·ACCESS 로그 읽는 법

`CONTENT request`는 **게이트웨이가 관측한 요청 내용**, `CONTENT response`는 **관측한 응답 내용**, `ACCESS`는 **그 HTTP 요청 한 건의 결과·사용량 요약**입니다. 세 로그의 `request`가 같으면 같은 요청입니다. 요청 내용 로그가 짧아도 실제 요청 전체가 짧았다는 뜻은 아닙니다.

## 요청과 응답을 구분해서 보기

로컬 pretty 출력은 방향별 제목과 메타데이터, 본문을 분리합니다. `>>>`는 클라이언트에서 공급자로 보내는 요청, `<<<`는 공급자에서 돌아온 응답입니다. 아래는 설명용 출력입니다. 시간·요청 ID·키 ID·본문은 예시이고, 사용량·지연·비용은 질문에 나온 요청의 접근 원장 수치입니다.

```text
12:00:00.000 DEBUG CONTENT ===== REQUEST >>> anthropic · claude-opus-5 =====
    request=example-request phase=request
    method=POST path=/anthropic/v1/messages stream=true
    detail=DEBUG truncated=false partial=false
    | [user]
    | <이 상세 수준에서 기록한 요청 텍스트>

12:00:06.480 DEBUG CONTENT ===== RESPONSE <<< anthropic · claude-opus-5 =====
    request=example-request phase=response
    method=POST path=/anthropic/v1/messages stream=true
    status=200 outcome=success
    detail=DEBUG truncated=false partial=false
    | [assistant]
    | <기록한 응답 텍스트>

12:00:06.480 INFO  ACCESS [local/live] anthropic · claude-opus-5 | 200 success | 6479.5ms
    request=example-request key=example-key | tokens=121992 (in=2 out=222 cache-write=121768 cache-read=0) usage=reported | cost=$0.766610 firstByte=2550.7ms
```

SSE도 요청 내용은 요청 시점에, 응답 내용은 스트림 종료 시 한 번 출력합니다. 비동기 내용 로거 때문에 콘솔에서 `CONTENT response`와 `ACCESS`의 표시 순서는 바뀔 수 있습니다. 순서보다 `request`로 연결하세요. JSON 형식에서는 방향 구분값 `phase=request/response`를 그대로 사용합니다.

## 공통 표시와 CONTENT 필드

| 표시 | 의미 |
|---|---|
| `12:00:00.000` | 해당 로그 이벤트의 벽시계 시각입니다. 처리 시간 자체가 아닙니다. 콘솔은 시·분·초·밀리초만 표시하고 JSON `timestamp`에는 날짜도 있습니다. |
| `DEBUG`, `TRACE` | 내용 기록 상세 수준입니다. DEBUG는 요청 메시지 배열의 마지막 항목과 응답 텍스트, TRACE는 최대 20개 이력과 system/instructions·도구 호출/결과까지 대상에 포함합니다. 마지막 항목이 도구 결과라면 DEBUG에서 생략될 수 있으며, 마지막 사람이 입력한 문장을 찾아 기록하는 방식은 아닙니다. INFO에서는 내용을 기록하지 않습니다. |
| `CONTENT` | 선택된 요청·응답 내용을 보여주는 진단 로그입니다. HTTP 헤더나 전송 원문 전체의 덤프가 아닙니다. |
| `request` / JSON `requestId` | 게이트웨이가 HTTP 요청마다 생성한 ID입니다. 세션 ID·API 키가 아니며, 재시도 등 별도 HTTP 요청에는 다른 ID가 붙습니다. |
| `phase=request` | 요청에서 선택한 내용입니다. 모델이 생성한 답변이 아닙니다. |
| `phase=response` | 응답에서 추출·재조립한 내용입니다. 스트리밍의 모든 원본 SSE 이벤트를 그대로 출력한 것은 아닙니다. |
| `anthropic` / `provider` | 전달 대상 공급자 분류입니다. 다른 경로에서는 `openai`, 기존 사용자 지정 업스트림에서는 `custom`이 나올 수 있습니다. |
| `claude-opus-5` / `model` | 게이트웨이가 관측한 모델 ID입니다. 요청과 응답의 모델명이 다르면 각 시점에 기록한 값이 다를 수 있습니다. |
| `method=POST`, `path=...` | 게이트웨이에서 처리한 HTTP 메서드와 경로입니다. |
| `stream=true` | 요청이 스트리밍 응답을 요구했다는 뜻입니다. 내용 로그를 매 청크마다 남긴다는 뜻은 아닙니다. |
| `status=200`, `outcome=success` | 응답 단계에서 알 수 있는 HTTP 상태와 게이트웨이의 완료 분류입니다. 아래 ACCESS 설명과 같습니다. |
| `detail=DEBUG` | 이 요청을 받아들일 때 정한 내용 수집 수준입니다. 처리 중 로거 수준이 바뀌었다고 기존 수집 내용이 TRACE로 확대되지는 않습니다. |
| `truncated=true` | 문자 수·이력 수·수집 구간 수 등 기록 한도에 걸렸다는 뜻입니다. **로그를 줄인 것**이며 공급자로 보낸 요청이나 사용자 응답을 잘랐다는 뜻이 아닙니다. |
| `truncated=false` | 해당 캡처에서 한도 초과가 표시되지 않았다는 뜻입니다. DEBUG의 이력 제외나 정책상 블록 생략이 없었다는 보장은 아닙니다. |
| `partial=true` | 내용 해석·스트림 수집이 불완전했거나 응답이 정상 완료되지 않았다는 뜻입니다. 너무 크거나 잘못된 이벤트, 연결 중단 등이 원인일 수 있습니다. |
| `partial=false` | 캡처가 불완전하다고 표시되지 않았다는 뜻입니다. 전체 프롬프트·모든 블록을 기록했다는 뜻은 아닙니다. |
| `\|` 뒤 본문 | 출력에서 내용 줄을 메타데이터와 구분하는 들여쓰기입니다. `\|` 자체가 프롬프트에 포함되었다는 뜻이 아닙니다. |
| `[user]`, `[assistant]` 등 | 추출한 메시지 역할·내용 종류를 구분하기 위해 캡처가 붙인 라벨입니다. 원문에 똑같은 괄호가 들어 있었다는 뜻이 아닙니다. |
| `[block omitted]` | 이전 출력의 일반 생략 표시입니다. **이 문구만으로 이미지·도구·thinking 중 어떤 블록이었는지 식별할 수 없습니다.** |

새 생략 표시는 캡처가 알아볼 수 있는 미디어·reasoning·DEBUG에서 숨긴 도구·지원하지 않는 블록을 구분합니다. 구체적인 생략 라벨이 있으면 그 범주까지 알 수 있지만 생략된 원문을 복원할 수는 없습니다. 생략은 로깅 관측 단계에만 적용하며 전달 본문에서 해당 블록을 삭제했다는 뜻이 아닙니다.

| 구체적 생략 표시 예 | 알 수 있는 범위 |
|---|---|
| `[block omitted: image media is not logged]` | image 형식의 미디어 블록을 로그에서 생략했습니다. |
| `[block omitted: reasoning is not logged]` | thinking/reasoning 계열을 내용 로그에 보관하지 않았습니다. |
| `[tool omitted: tool_use requires TRACE]` | DEBUG 수준에서 도구 호출 내용을 숨겼습니다. |
| `[block omitted: unsupported content type=...]` | 캡처가 지원하지 않는 블록 종류입니다. 종류를 안전하게 식별할 수 없으면 `unknown`입니다. |
| `[no text captured]` | 출력할 텍스트가 없었습니다. 실제 요청·응답 본문이 비어 있었다는 증거는 아닙니다. |

## ACCESS 필드

| 표시 | 이 요청에서 읽을 수 있는 내용 |
|---|---|
| `INFO ACCESS` | 요청 결과·사용량 요약입니다. 현재 요약 로거는 4xx에 WARN, 5xx·업스트림 오류에 ERROR, 나머지에 INFO를 사용합니다. 별도 JSONL 원장은 INFO로 기록합니다. |
| `[local/live]` | 실행 환경이 `local`, 트래픽 라벨이 `live`입니다. 로컬 실행이지만 실제 공급자를 호출하는 구성입니다. 로컬이라는 이유로 무료·모의 요청이 되지 않습니다. |
| `200` | 클라이언트로 전달한 HTTP 상태입니다. 스트리밍에서는 상태 전송 뒤 오류가 날 수 있으므로 상태만으로 끝까지 성공했다고 판단하지 않습니다. |
| `success` | 취소·관측된 업스트림 오류 없이 성공 상태로 완료됐다는 분류입니다. 답변 품질이나 공급자 청구 확정의 의미는 아닙니다. 다른 값은 `http_error`, `upstream_error`, `cancelled`입니다. |
| `6479.5ms` / JSON `totalMs` | 게이트웨이 요청 레코드 생성부터 완료/종료 처리까지 약 **6.480초**입니다. 공급자 내부 추론 시간만 측정한 값이 아닙니다. |
| `key=...` / JSON `keyId` | 실제 전달한 인증 값의 HMAC 식별자입니다. 원문 키가 아니며 콘솔에는 앞 16자까지만 보입니다. 같은 키의 요청 집계용이고, 실제 사람의 이름·계정 ID는 아닙니다. JSON에는 전체 식별자가 있습니다. |
| `tokens=121992` | 관측한 사용량의 총 토큰 수입니다. 아래 입력·출력·캐시 항목을 합쳐 설명할 수 있습니다. |
| `in=2` | **캐시 쓰기·읽기를 제외한** 일반 입력 토큰입니다. 전체 입력이 2토큰이라는 뜻이 아닙니다. |
| `out=222` | 공급자 usage에서 관측한 출력 토큰입니다. 로그에 보이는 글자 수나 단어 수와 같지 않습니다. |
| `cache-write=121768` | 캐시 생성 입력 토큰입니다. 콘솔은 5분·1시간 캐시 쓰기를 합칩니다. 이 요청의 JSON 원장은 `cacheWriteTokens=121768`, `cacheWrite1hTokens=0`이므로 관측된 쓰기는 모두 5분 항목입니다. |
| `cache-read=0` | 이 요청에서 캐시 읽기 토큰이 0으로 보고됐습니다. 왜 읽기를 사용하지 않았는지, 이전에 캐시가 있었는지는 이 수치만으로 알 수 없습니다. |
| `usage=reported` | 공급자 사용량을 수집했고 취소·업스트림 오류에 따른 부분 사용량으로 분류하지 않았습니다. `missing`은 사용량 없음, `partial`은 일부 관측 후 중단/오류, `not_applicable`은 모델 조회 등 생성 사용량 대상이 아닌 요청입니다. |
| `cost=$0.766610` | 관측한 토큰과 **저장소의 모델 단가표**로 계산한 USD 추정 비용입니다. 현재 공급자 공식 요율을 실시간 확인한 금액이나 확정 청구서는 아닙니다. 단가를 모르면 `unknown`입니다. |
| `firstByte=2550.7ms` / JSON `ttftMs` | 요청 시작 후 첫 응답 바이트를 전달하는 처리 지점까지 약 **2.551초**입니다. SSE 시작 이벤트일 수도 있어 **첫 생성 토큰까지의 시간으로 단정하면 안 됩니다.** |

CONTENT의 `partial`은 **내용 캡처 완전성**, ACCESS의 `usage`는 **토큰 사용량 수집 상태**입니다. 서로 다른 정보이므로 내용 일부를 생략해도 `usage=reported`일 수 있습니다.

`keyId`를 재시작 뒤에도 같은 값으로 집계하려면 고정된 `GW_KEY_ID_SECRET`이 필요합니다. 설정하지 않으면 기동할 때마다 임의의 HMAC 비밀값을 생성하므로 같은 개인 API 키도 재시작 후 식별자가 달라집니다.

### 이 요청의 토큰과 비용 계산

```text
일반 입력      2
캐시 쓰기 121,768
캐시 읽기      0
출력         222
────────────────
합계     121,992 토큰

전체 입력 = 2 + 121,768 + 0 = 121,770 토큰
```

이 요청에 적용된 저장소 단가표는 일반 입력 100만 토큰당 $5, 출력 $25이고, 구현의 5분 캐시 쓰기 배수는 1.25입니다. **이 로컬 계산 규칙**을 대입하면 다음과 같습니다.

```text
일반 입력: 2 × 5 / 1,000,000                  = $0.000010
캐시 쓰기: 121,768 × 5 × 1.25 / 1,000,000    = $0.761050
출력:      222 × 25 / 1,000,000              = $0.005550
합계                                          $0.766610
```

이 수치에서는 캐시 쓰기 항목이 비용 대부분을 차지합니다. 그러나 121,768토큰의 구체적 구성(시스템 프롬프트·도구 정의·파일·과거 대화 등)이나 캐시 쓰기가 발생한 이유는 이 로그만으로 구분할 수 없습니다. 화면의 짧은 최신 메시지만 보고 큰 입력 전체의 원인을 특정하지 마세요.

## 같은 요청의 원장 찾기

```bash
jq 'select(.requestId == "로그의-request-값")' var/access.log
```

JSON에는 전체 `keyId`, 캐시 TTL별 토큰, `method`·`path`, 공급자의 `upstreamRequestId` 등이 있습니다. 오류가 있으면 `upstreamErrorReason`, `retryAfterSeconds`도 함께 확인합니다. 내용 로그 저장을 켠 경우 `var/content.log`에서도 같은 `requestId`와 `phase`로 요청·응답을 연결할 수 있습니다.

기록 수준·길이 한도·파일 보관 설정은 [README의 내용 로그 설정](../README.md#요청응답-내용-로그-intellij와-운영)을 참고하세요.
