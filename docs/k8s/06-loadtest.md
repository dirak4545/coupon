# 06. replica 3개로 초과발급 재검증 ⭐

이 브랜치의 존재 이유. 여기까지가 본론이다.

## 검증할 명제

part-2-3 에서 재고 차감을 Redis Lua 로 옮겼다.

```lua
-- src/main/resources/lua/issue.lua
local remaining = tonumber(redis.call('GET', KEYS[1]) or '0')
if remaining <= 0 then
    return 0
end
redis.call('DECR', KEYS[1])
return 1
```

Lua 스크립트는 Redis 에서 **원자적으로** 실행된다. 하지만 그때 앱은 **컨테이너 1개**였다.
그래서 "JVM 안에서만 원자적인 것 아니냐"는 반박이 가능했다.

이제 진짜로 확인한다.

| 실험 | 앱 JVM 수 | 기대 결과 |
|---|---|---|
| A | 1개 | 정확히 5,000장 (part-2-3 재현) |
| B | **3개** | **정확히 5,000장** ← 핵심 |
| C | 5개 | 정확히 5,000장 |

**B 와 C 가 통과하면**: 원자성이 프로세스가 아니라 **Redis 라는 공유 지점**에 있다는 증명이다.
JVM 이 몇 개든, 어느 노드에 있든 상관없다.

**만약 실패한다면**: 재고 차감이 Redis 가 아니라 애플리케이션 메모리에 의존하고 있다는 뜻이다.
(예: `synchronized`, `AtomicInteger`, JVM 로컬 캐시 — 이건 part-2 초반 버전이 그랬다)

## 0) 스크립트를 k8s 에서도 쓰게 만들기

문제: `reset.sh`, `verify.sh` 가 `docker compose exec mysql ...` 로 되어 있다.
k8s 에는 compose 가 없다.

`create_coupon.sh` 와 `over_issuance.js` 는 `localhost:8080` 을 쓰는데,
**kind 포트 매핑 덕분에 이건 그대로 동작한다.** 고칠 건 DB 접근부뿐이다.

### `scripts/load/lib/db.sh` (새로 만들기)

```bash
#!/usr/bin/env bash
# MySQL/Redis 접근을 실행 환경별로 추상화한다.
#
#   RUNTIME=docker  (기본) : docker compose
#   RUNTIME=k8s            : kubectl
#
# 사용:
#   source "$(dirname "$0")/lib/db.sh"
#   mysql_exec -t coupon -e "SELECT 1"

RUNTIME="${RUNTIME:-docker}"
K8S_NS="${K8S_NS:-coupon}"

mysql_exec() {
  case "$RUNTIME" in
    docker)
      docker compose exec -T -e MYSQL_PWD=coupon mysql mysql -ucoupon "$@"
      ;;
    k8s)
      # kubectl exec 에는 -e 가 없어서 env 로 환경변수를 넘긴다.
      # statefulset/mysql 로 지정하면 kubectl 이 알아서 Pod 을 골라준다.
      kubectl -n "$K8S_NS" exec -i statefulset/mysql -- \
        env MYSQL_PWD=coupon mysql -ucoupon "$@"
      ;;
    *)
      printf 'RUNTIME 값이 잘못됨: %s (docker | k8s)\n' "$RUNTIME" >&2
      return 1
      ;;
  esac
}

redis_exec() {
  case "$RUNTIME" in
    docker) docker compose exec -T redis redis-cli "$@" ;;
    k8s)    kubectl -n "$K8S_NS" exec -i statefulset/redis -- redis-cli "$@" ;;
    *)
      printf 'RUNTIME 값이 잘못됨: %s (docker | k8s)\n' "$RUNTIME" >&2
      return 1
      ;;
  esac
}
```

### `scripts/load/reset.sh` 수정

`docker compose exec ...` 줄을 `mysql_exec` 호출로 바꾼다.

```bash
#!/usr/bin/env bash
# coupon/issuance TRUNCATE 후 행 수 확인 (둘 다 0 이어야 정상).

set -euo pipefail
cd "$(dirname "$0")/../.."
source "$(dirname "$0")/lib/db.sh"

printf '\n\033[1;36m===== coupon, issuance 데이터 리셋 (RUNTIME=%s) =====\033[0m\n' "$RUNTIME"

mysql_exec -t coupon -e "
  SET FOREIGN_KEY_CHECKS=0; TRUNCATE issuance; TRUNCATE coupon; SET FOREIGN_KEY_CHECKS=1;
  SELECT (SELECT COUNT(*) FROM coupon)   AS coupon_rows,
         (SELECT COUNT(*) FROM issuance) AS issuance_rows;
"

# Redis 에 남은 옛 재고 키를 정리한다.
# TRUNCATE 로 AUTO_INCREMENT 가 1 로 돌아가므로, 옛 실행의 재고 키와 id 가 겹칠 수 있다.
# (학습용 클러스터 전제. 운영에서 FLUSHALL 은 당연히 금지)
redis_exec FLUSHALL > /dev/null
printf 'redis 재고 키 초기화 완료\n'
```

> 특정 키만 지우고 싶다면 `redis_exec --scan --pattern 'coupon:*:stock'` 로 목록을 뽑아
> 하나씩 `redis_exec DEL <key>` 하면 된다. 실습에서는 `FLUSHALL` 이 단순해서 낫다.

### `scripts/load/part-2/verify.sh` 수정

두 군데의 `docker compose exec ... mysql` 을 바꾼다.

```bash
source "$(dirname "$0")/../lib/db.sh"

# 1) 표 출력
mysql_exec -t coupon -e "$SQL"

# 2) batch 모드로 다시 받아 판정
read field total rows over count < <(mysql_exec -BN coupon -e "$SQL")
```

나머지는 그대로 둔다.

### 확인

```bash
RUNTIME=k8s ./scripts/load/reset.sh
```

`coupon_rows 0 / issuance_rows 0` 이 나오면 성공이다.

## 1) 실험 A — replica 1개 (기준선)

```bash
kubectl -n coupon delete hpa coupon-service --ignore-not-found   # HPA 가 replica 를 건드리지 않게
kubectl -n coupon scale deployment/coupon-service --replicas=1
kubectl -n coupon rollout status deployment/coupon-service
kubectl -n coupon get pods -o wide
```

```bash
RUNTIME=k8s ./scripts/load/part-2/run.sh
```

> **노트북이 버거우면** `over_issuance.js` 의 `rate: 5000` 을 `1500` 정도로 낮춘다.
> 절대 처리량이 아니라 **정합성**을 보는 실험이라 rate 를 낮춰도 결론은 같다.
> (다만 재고 5,000장을 다 소진할 만큼은 되어야 한다: `rate × duration ≥ 5000`)

기대 결과:

```
issued_quantity  total_quantity  issuance_rows  over_issuance  count_match
5000             5000            5000           OK             OK
PASS
```

## 2) 실험 B — replica 3개 ⭐ 핵심

```bash
kubectl -n coupon scale deployment/coupon-service --replicas=3
kubectl -n coupon rollout status deployment/coupon-service
kubectl -n coupon get pods -o wide      # 서로 다른 노드에 있는지 확인
```

```bash
RUNTIME=k8s ./scripts/load/part-2/run.sh
```

### 기대 결과

```
issued_quantity  total_quantity  issuance_rows  over_issuance  count_match
5000             5000            5000           OK             OK
PASS
```

### 왜 이게 대단한가

지금 이 순간 일어난 일을 정확히 말해보자.

- **JVM 3개**가 서로 다른 노드(다른 리눅스 커널, 다른 메모리 공간)에서 돌았다
- 세 프로세스는 서로의 존재를 **전혀 모른다**
- 초당 수천 건의 발급 요청이 세 프로세스에 무작위로 뿌려졌다
- 그런데도 **정확히 5,000장**이 나왔다

`synchronized` 도, `AtomicInteger` 도, 락 매니저도 여기서는 무력하다. 프로세스 경계를 못 넘으니까.
정합성을 지킨 건 오직 **Redis 라는 단일 직렬화 지점**이다.

> **이것이 분산 시스템의 제1원칙이다.**
> 정합성은 프로세스 안에서 만들어지지 않는다. **모두가 지나가는 하나의 좁은 문**에서 만들어진다.
> 그 문은 Redis 일 수도, DB 의 행 락일 수도, Kafka 파티션일 수도 있다.
> 하지만 반드시 **어딘가 하나**여야 한다.

### 부하가 정말 3개에 나뉘었나 확인

부하를 거는 동안 다른 터미널에서:

```bash
kubectl -n coupon top pods
```

세 Pod 의 CPU 가 모두 올라가야 한다. 하나만 올라간다면
`Service` 의 selector 나 endpoints 를 다시 봐야 한다.

## 3) 실험 C — replica 5개, 그리고 새로운 병목

```bash
kubectl -n coupon scale deployment/coupon-service --replicas=5
kubectl -n coupon rollout status deployment/coupon-service
RUNTIME=k8s ./scripts/load/part-2/run.sh
```

`issuance_rows` 는 여전히 5,000 이어야 한다. **정합성은 replica 수와 무관하다.**

하지만 k6 출력의 다른 숫자를 보자.

```
http_req_failed ...............: 12.3%
http_req_duration .............: p(95)=... 
```

**정합성은 그대로인데 에러율과 지연이 오히려 나빠졌을 수 있다.** 왜?

```
Pod 5개 × HikariCP 기본 풀 10개 = DB 커넥션 50개 요구
MySQL Pod 1개, 기본 max_connections = 151
```

앱을 늘렸지만 **MySQL 도 Redis 도 여전히 1개**다.
Pod 이 늘수록 커넥션 경합, 락 경합, Redis 큐잉이 심해진다.

```bash
kubectl -n coupon exec statefulset/mysql -- \
  mysql -uroot -proot -e "SHOW STATUS LIKE 'Threads_connected'; SHOW VARIABLES LIKE 'max_connections';"

kubectl -n coupon exec statefulset/redis -- redis-cli INFO clients | head -5
```

### 여기서 얻는 교훈

> **스케일 아웃은 상태 없는 계층에만 공짜다.**
> 상태를 가진 계층(DB, Redis)이 그대로면, 앱을 늘리는 건 병목 앞의 대기줄을 늘리는 것에 불과하다.
> 심하면 **부하를 아래로 증폭시켜 더 나빠진다.**

`kubectl scale` 이 너무 쉬워서 생기는 착시다. 숫자를 올리기 전에
**"내가 늘리는 계층이 진짜 병목인가?"** 를 물어야 한다.

## 4) 실험 D — Redis 유실 시나리오 ⭐

03 에서 예고한 조용한 장애를 직접 만들어본다.

```bash
kubectl -n coupon scale deployment/coupon-service --replicas=3
RUNTIME=k8s ./scripts/load/reset.sh
COUPON_ID=$(./scripts/load/create_coupon.sh)
echo "쿠폰 ID: $COUPON_ID"
```

정상 발급 확인:

```bash
curl -s -X POST localhost:8080/api/coupons/$COUPON_ID/issue -H 'X-User-Id: 1' | jq
kubectl -n coupon exec statefulset/redis -- redis-cli GET "coupon:$COUPON_ID:stock"
# "4999"
```

이제 재고 키만 지운다 (= Redis 데이터 유실을 시뮬레이션):

```bash
kubectl -n coupon exec statefulset/redis -- redis-cli DEL "coupon:$COUPON_ID:stock"
```

다시 발급:

```bash
curl -i -s -X POST localhost:8080/api/coupons/$COUPON_ID/issue -H 'X-User-Id: 2'
```

**매진 응답이 온다.** DB 를 보자.

```bash
kubectl -n coupon exec statefulset/mysql -- \
  env MYSQL_PWD=coupon mysql -ucoupon -t coupon \
  -e "SELECT id, total_quantity, issued_quantity FROM coupon;"
```

```
id  total_quantity  issued_quantity
1   5000            1
```

**DB 에는 4,999장이 남아 있는데 서비스는 전부 매진이라고 답한다.**

그리고 앱 로그를 보자.

```bash
kubectl -n coupon logs -l app=coupon-service --tail=50 --prefix
```

**에러가 하나도 없다.** 헬스체크도 `UP` 이다. Pod 도 `1/1 Running` 이다.
모니터링 대시보드의 모든 그래프가 초록색인데, 매출은 0 이다.

### 이게 이 실습에서 가장 중요한 교훈일 수 있다

> **k8s 에서 Pod 재시작은 예외가 아니라 일상이다.**
> 노드 교체, 스케일 조정, OOM, 클러스터 업그레이드, 스팟 인스턴스 회수 — 전부 정상 동작이다.
> 그래서 **"이 상태가 사라지면 무슨 일이 생기나?"** 를 모든 상태에 대해 물어야 한다.
> 그 답이 "조용히 잘못된 결과를 낸다" 라면, 그건 설계 결함이다.

지금 구조의 대응책 (구현은 이 브랜치 범위 밖):

1. **AOF + PVC** — 이미 했다. Pod 재시작은 버티지만 볼륨 유실은 못 버틴다.
2. **워밍업**: 앱 기동/키 미스 시 `total_quantity - issued_quantity` 로 Redis 를 복구한다.
3. **미스와 0 을 구분**: Lua 에서 `GET` 이 `nil` 이면 매진이 아니라 **에러**로 처리해
   조용한 실패를 시끄러운 실패로 바꾼다. (`or '0'` 이 바로 이 조용함의 원인이다)
4. **관측**: 재고 키 존재 여부를 헬스/메트릭으로 노출한다.

3번이 가장 값싸고 효과가 크다. **"조용히 틀리느니 시끄럽게 실패하라(fail loudly)."**

복구:

```bash
RUNTIME=k8s ./scripts/load/reset.sh
```

## 5) 실험 E — 부하 중 무중단 배포

```bash
# 터미널 A
RUNTIME=k8s ./scripts/load/part-2/run.sh
```

```bash
# 터미널 B: k6 가 도는 도중에
kubectl -n coupon rollout restart deployment/coupon-service
kubectl -n coupon rollout status  deployment/coupon-service
```

끝나고 `verify.sh` 결과를 본다.

**배포 도중에도 초과발급이 없어야 한다.** 왜?
Pod 이 죽고 새로 뜨는 와중에도 재고의 단일 진실은 Redis 에 있기 때문이다.
앱은 완전히 소모품(stateless)이라 언제 죽어도 정합성에 영향이 없다.

k6 의 `http_req_failed` 는 약간 오를 수 있다. 그건 정합성 문제가 아니라
종료 중 커넥션 처리(`preStop`, graceful shutdown)의 문제다. `preStop` 을 빼고 다시 해보면
차이가 확연하다 — 좋은 비교 실험이다.

## 실험 결과 기록표

직접 채워보자.

| 실험 | replicas | issuance_rows | over_issuance | count_match | http_req_failed | 메모 |
|---|---|---|---|---|---|---|
| A | 1 | | | | | |
| B | 3 | | | | | |
| C | 5 | | | | | |
| D | 3 (Redis 키 삭제) | | | | | |
| E | 3 (배포 중) | | | | | |

## 체크포인트

1. replica 를 3개로 늘려도 초과발급이 없는 이유를 한 문장으로.
2. `synchronized` 로 재고를 지켰다면 실험 B 에서 무슨 일이 일어났을까?
3. replica 를 늘렸는데 처리량이 그만큼 안 늘고 에러가 늘었다. 원인은?
4. Redis 재고 키가 사라졌는데 모니터링이 전부 초록색인 이유는? 어떻게 고치나?

<details>
<summary>답</summary>

1. 재고 차감이 앱이 아니라 **Redis 안에서 원자적으로** 일어나고, 모든 Pod 이 그 하나의 Redis 를 지나기 때문.
2. JVM 3개가 각자 자기 락만 잡으므로 서로를 못 막는다. 최악의 경우 재고의 **3배**까지 초과발급된다.
   (앱 인스턴스 수에 비례해 결함이 커진다)
3. 상태를 가진 계층(MySQL 커넥션, Redis 싱글 스레드)이 여전히 1개라 그게 병목.
   앱을 늘리면 병목 앞 대기줄만 길어지고 커넥션 경합이 심해진다.
4. Lua 의 `GET ... or '0'` 이 "키 없음"과 "재고 0"을 같은 것으로 취급해서
   정상적인 매진 응답으로 처리하기 때문. 둘을 구분해 키 미스는 에러로 올리고,
   기동/미스 시 DB 기준으로 재고를 워밍업하면 된다.
</details>

---
다음: [07-kustomize.md](07-kustomize.md)
