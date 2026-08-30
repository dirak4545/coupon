# 05. Probe, 롤링 업데이트, HPA, PDB

앱이 떴다. 이제 **"운영되는" 상태**로 만든다.

## 1) Probe 3종 정리

| Probe | 실패하면 | 언제 도나 | 무엇을 넣나 |
|---|---|---|---|
| **startup** | 재시작 | 기동 중에만 | 앱이 떴는지 |
| **liveness** | **재시작** | startup 성공 후 계속 | **자기 자신만** (교착, 힙 고갈) |
| **readiness** | 트래픽에서 제외 | startup 성공 후 계속 | 자신 + **의존성**(DB/Redis) |

### startupProbe 의 진짜 역할

startupProbe 가 **성공하기 전까지 liveness/readiness 는 실행조차 되지 않는다.**
Spring Boot + JPA 앱은 기동에 10~60초가 걸리는데, 이 유예가 없으면 기동 중에
liveness 가 실패해서 재시작 → 또 기동 → 또 실패의 `CrashLoopBackOff` 에 빠질 수 있다.

최대 대기 시간 = `periodSeconds × failureThreshold` = `5 × 30` = **150초**.

### 잘못 만든 헬스체크가 서비스를 죽인다

가장 흔한 안티패턴:

```yaml
# ❌ 절대 하지 말 것
livenessProbe:
  httpGet:
    path: /actuator/health      # 여기엔 db, redis 가 다 들어있다
```

`/actuator/health` 는 **모든** 헬스 인디케이터의 합이다. DB 가 흔들리면 이게 DOWN 이 되고,
liveness 가 그걸 보면 **DB 장애가 앱 전멸로 증폭된다.**
그래서 반드시 `/actuator/health/liveness` (= `livenessState` 만) 를 쓴다.

### 직접 확인해보기

```bash
kubectl -n coupon exec deploy/coupon-service -- \
  curl -s localhost:8080/actuator/health/liveness
# {"status":"UP","components":{"livenessState":{"status":"UP"}}}

kubectl -n coupon exec deploy/coupon-service -- \
  curl -s localhost:8080/actuator/health/readiness
# readinessState + db + redis 가 함께 나온다
```

### 실험: Redis 를 내리면 어떻게 되나 ⭐

```bash
# 터미널 A: Pod 상태를 지켜본다
kubectl -n coupon get pods -w
```

```bash
# 터미널 B: Redis 를 0개로 줄인다
kubectl -n coupon scale statefulset redis --replicas=0
```

**관찰 포인트**: 앱 Pod 이 `1/1` → `0/1` 로 바뀌지만 **`RESTARTS` 는 0 을 유지한다.**

```bash
kubectl -n coupon get endpoints coupon-service   # 비어간다
curl -i localhost:8080/actuator/health/readiness # 503
```

이게 바로 04 에서 설계한 대로다: **트래픽에서만 빠지고, 재시작은 안 한다.**

복구:

```bash
kubectl -n coupon scale statefulset redis --replicas=1
kubectl -n coupon wait --for=condition=ready pod/redis-0 --timeout=120s
kubectl -n coupon get pods    # 재시작 없이 1/1 로 알아서 복귀
```

**재시작 없이 자동 복귀** — readiness 를 제대로 설계하면 이렇게 된다.

> ⚠️ 이 실험 후에는 Redis 의 재고 키가 사라졌을 수 있다. 06 을 하기 전에
> `./scripts/load/...` 로 쿠폰을 새로 만들어야 한다. (06 에서 자세히)

## 2) 롤링 업데이트 관찰하기

```bash
# 터미널 A
kubectl -n coupon get pods -w
```

```bash
# 터미널 B
kubectl -n coupon rollout restart deployment/coupon-service
kubectl -n coupon rollout status  deployment/coupon-service
```

`maxSurge: 1, maxUnavailable: 0` 이라 **새 Pod 이 Ready 된 뒤에야** 옛 Pod 이 내려간다.
`READY` 열의 합이 항상 3 이상으로 유지되는지 보자.

### 무중단인지 실제로 측정

```bash
# 터미널 C: 배포하는 동안 계속 때린다
while true; do
  code=$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/health)
  [ "$code" = "200" ] || echo "$(date +%T) FAIL $code"
  sleep 0.1
done
```

배포 중에 `FAIL` 이 하나도 안 찍히면 성공이다.
`preStop` 을 지우고 다시 해보면 차이를 체감할 수 있다 (좋은 실험이다).

### 롤백

```bash
kubectl -n coupon rollout history deployment/coupon-service
kubectl -n coupon rollout undo    deployment/coupon-service
kubectl -n coupon rollout undo    deployment/coupon-service --to-revision=2
```

`revisionHistoryLimit: 3` 이라 최근 3개까지 되돌릴 수 있다.

## 3) 수동 스케일 — 이 브랜치의 실험 손잡이

```bash
kubectl -n coupon scale deployment/coupon-service --replicas=5
kubectl -n coupon get pods -o wide
kubectl -n coupon scale deployment/coupon-service --replicas=3
```

**5초 만에 JVM 5개짜리 분산 환경이 된다.** compose 로는 이만큼 쉽게 못 한다.
06 에서 이 손잡이를 돌려가며 초과발급을 검증한다.

## 4) HPA (자동 스케일)

### metrics-server 설치

HPA 는 Pod 의 CPU/메모리 사용량을 알아야 하는데, kind 에는 metrics-server 가 없다.

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

kind 의 kubelet 인증서는 자체 서명이라 그대로면 연결에 실패한다. 플래그를 하나 추가한다.

```bash
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

kubectl -n kube-system rollout status deployment/metrics-server
```

> `--kubelet-insecure-tls` 는 **로컬 실습 전용**이다. 운영 클러스터에서 쓰면 안 된다.

확인 (수집까지 30초쯤 걸린다):

```bash
kubectl top nodes
kubectl -n coupon top pods
```

### `k8s/base/hpa.yaml`

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: coupon-service
  namespace: coupon
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: coupon-service

  minReplicas: 2
  maxReplicas: 8

  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          # requests.cpu(500m) 대비 60%. 즉 Pod 평균 300m 을 넘으면 늘린다.
          averageUtilization: 60

  behavior:
    scaleUp:
      # 트래픽 급증에는 빠르게 반응
      stabilizationWindowSeconds: 30
      policies:
        - type: Percent
          value: 100          # 30초마다 최대 2배까지
          periodSeconds: 30
    scaleDown:
      # 축소는 보수적으로. 급하게 줄이면 다시 늘리는 진동(flapping)이 생긴다.
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
```

```bash
kubectl apply -f k8s/base/hpa.yaml
kubectl -n coupon get hpa -w
```

```
NAME             REFERENCE                   TARGETS   MINPODS  MAXPODS  REPLICAS
coupon-service   Deployment/coupon-service   12%/60%   2        8        3
```

### HPA 에서 반드시 알아야 할 것

**`requests.cpu` 가 없으면 HPA 는 동작하지 않는다.**
사용률 = 실제 사용량 / **request** 이므로 분모가 없으면 계산이 안 된다.
`TARGETS` 가 `<unknown>/60%` 로 나오면 대개 이 문제거나 metrics-server 문제다.

**scaleUp 은 빠르게, scaleDown 은 느리게.**
비대칭이 정석이다. 늦게 늘리면 장애가 나고, 급히 줄이면 진동한다.

**HPA 와 `replicas` 는 싸운다.**
HPA 를 만든 뒤 매니페스트의 `replicas: 3` 은 무의미해진다 (HPA 가 계속 덮어쓴다).
실무에서는 HPA 를 쓸 때 Deployment 에서 `replicas` 필드를 아예 뺀다.
**06 의 실험 동안에는 replica 수를 고정하고 싶으니, HPA 를 잠시 지우거나
`minReplicas = maxReplicas` 로 고정하는 게 좋다.**

**HPA 로는 이 서비스의 근본 병목을 못 푼다.**
쿠폰 발급은 결국 **Redis 하나**와 **MySQL 하나**를 지난다.
앱 Pod 을 8개로 늘려도 DB 커넥션과 Redis 싱글 스레드가 상한이다.
오히려 Pod 이 늘면 커넥션 풀 총량이 늘어 **DB 가 먼저 무너질 수 있다.**

```
Pod 8개 × HikariCP 기본 10 = 커넥션 80개
MySQL 기본 max_connections = 151
```

**"스케일 아웃"은 상태 없는(stateless) 계층에만 공짜다.** 이건 06 에서 직접 확인한다.

### 부하로 HPA 동작 보기

```bash
# 터미널 A
kubectl -n coupon get hpa,pods -w
```

```bash
# 터미널 B
k6 run -e COUPON_ID=1 scripts/load/part-2/over_issuance.js
```

CPU 가 60% 를 넘으면 REPLICAS 가 올라가고, 부하가 끝나고 5분(stabilization window) 뒤 내려간다.

## 5) PDB (PodDisruptionBudget)

`k8s/base/pdb.yaml`

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: coupon-service
  namespace: coupon
spec:
  # 자발적 중단(노드 드레인, 클러스터 업그레이드) 시 최소 2개는 살려둔다.
  minAvailable: 2
  selector:
    matchLabels:
      app: coupon-service
```

```bash
kubectl apply -f k8s/base/pdb.yaml
kubectl -n coupon get pdb
```

### PDB 가 막는 것과 못 막는 것

| 중단 유형 | 예 | PDB 가 막나 |
|---|---|---|
| **자발적**(voluntary) | `kubectl drain`, 클러스터 업그레이드, 노드 축소 | ✅ 막는다 |
| **비자발적**(involuntary) | 노드 하드웨어 고장, OOMKill, 커널 패닉 | ❌ 못 막는다 |

PDB 는 "**관리자가 일부러 Pod 을 내릴 때**" 몇 개까지 허용할지를 정하는 것이지,
장애로부터 보호하는 장치가 아니다. 자주 오해하는 부분이다.

### 직접 확인

```bash
kubectl drain coupon-worker --ignore-daemonsets --delete-emptydir-data --force
kubectl -n coupon get pods -o wide     # 다른 노드로 옮겨간다
kubectl uncordon coupon-worker
```

`minAvailable: 2` 인데 replica 가 2개뿐이면 **drain 이 영원히 멈춘다.**
`minAvailable` 은 replica 보다 작아야 한다. (또는 `maxUnavailable: 1` 을 쓰는 게 더 안전하다)

## 체크포인트

1. Redis 를 내렸을 때 앱 Pod 이 재시작하지 **않은** 이유는?
2. HPA 의 `TARGETS` 가 `<unknown>` 이다. 원인 후보 두 가지는?
3. Pod 을 8개로 늘렸는데 처리량이 2배도 안 늘었다. 어디를 봐야 하나?
4. 노드 하드웨어가 고장 나서 Pod 3개가 한꺼번에 죽었다. PDB 가 막아주나?

<details>
<summary>답</summary>

1. Redis 는 readiness 그룹에만 들어있고 liveness 그룹(기본값 `livenessState`)에는 없어서.
   readiness 실패는 엔드포인트 제외만 하고 재시작하지 않는다.
2. (a) metrics-server 미설치/미동작 (b) 컨테이너에 `resources.requests.cpu` 가 없음.
3. 앱 아래의 공유 자원 — Redis(싱글 스레드), MySQL(커넥션 수, 락, 디스크 I/O).
   `kubectl top pods` 로 앱 CPU 가 안 오르는데 지연만 늘면 병목은 아래에 있다.
4. **못 막는다.** PDB 는 자발적 중단(drain 등)만 통제한다. 비자발적 중단 대비는
   `topologySpreadConstraints` 로 흩뿌려 두는 것이다.
</details>

---
다음: [06-loadtest.md](06-loadtest.md) ← 이 브랜치의 핵심
