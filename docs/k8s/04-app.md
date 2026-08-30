# 04. 앱 배포 — Actuator, Deployment, Service

## 0) 먼저 코드 수정: Actuator 추가

지금 이 앱에는 **헬스체크 엔드포인트가 없다.**
`GET /` 를 때리면 404 다. 쿠버네티스가 "이 Pod 이 트래픽을 받을 준비가 됐는지" 물어볼 곳이 없다.

TCP 소켓 probe(포트가 열렸는지만 확인)로 때울 수도 있지만, 그건
**"JVM 이 살아있다"** 만 알려줄 뿐 **"DB/Redis 에 붙었다"** 는 못 알려준다.
Spring Boot Actuator 가 이걸 정확히 채워준다.

### `build.gradle.kts`

```kotlin
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")   // 추가
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	// ... 기존 그대로
}
```

확인:

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -i actuator | head
```

### `src/main/resources/application.yaml`

파일 끝에 추가한다.

```yaml
server:
  # 종료 신호를 받으면 처리 중인 요청을 끝내고 나간다. k8s 롤링 업데이트에 필수.
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      # /actuator/health/liveness, /actuator/health/readiness 를 활성화
      probes:
        enabled: true
      show-details: always
      group:
        # 기본 readiness 그룹에는 readinessState 만 들어간다.
        # DB/Redis 가 끊기면 트래픽을 받지 말아야 하므로 명시적으로 포함시킨다.
        readiness:
          include: readinessState, db, redis
```

### ⭐ liveness 와 readiness 를 구분하는 핵심 원칙

```
liveness  실패 → 컨테이너를 재시작한다
readiness 실패 → Service 엔드포인트에서 뺀다 (재시작 안 함)
```

**외부 의존성(DB, Redis)은 readiness 에만 넣고, liveness 에는 절대 넣지 않는다.**

왜? DB 가 잠깐 흔들렸다고 하자.

- readiness 에만 있으면: 모든 Pod 이 트래픽에서 빠진다 → DB 회복 → 자동 복귀. ✅
- liveness 에도 있으면: 모든 Pod 이 **동시에 재시작**한다 → 다 같이 커넥션 폭풍으로 재접속 →
  DB 가 더 힘들어짐 → 또 실패 → **연쇄 재시작 루프**. ❌

**DB 장애를 앱 전멸로 증폭시키는 것**, 이게 실무에서 가장 흔한 probe 사고다.
그래서 위 설정에서 `liveness` 그룹은 손대지 않았다 (기본값 = `livenessState` 만).

### 이미지 재빌드

```bash
./gradlew clean jibDockerBuild
kind load docker-image coupon-service:latest --name coupon
```

## 1) Deployment + Service

`k8s/base/app.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: coupon-service
  namespace: coupon
  labels:
    app: coupon-service
spec:
  replicas: 3                      # ★ 이 브랜치의 실험 변수
  revisionHistoryLimit: 3

  selector:
    matchLabels:
      app: coupon-service

  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1                  # 정원보다 1개까지 더 띄울 수 있음
      maxUnavailable: 0            # 무중단: 새 Pod 이 Ready 된 뒤에야 옛 Pod 을 내린다

  template:
    metadata:
      labels:
        app: coupon-service
    spec:
      # 종료 유예 시간. graceful shutdown(20s) 보다 넉넉해야 한다.
      terminationGracePeriodSeconds: 40

      # Pod 을 노드에 최대한 고르게 흩뿌린다.
      # 노드 하나가 죽어도 전부 죽지 않게 하는 장치.
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway     # 못 맞춰도 스케줄은 되게 (DoNotSchedule 이면 Pending)
          labelSelector:
            matchLabels:
              app: coupon-service

      containers:
        - name: app
          image: coupon-service:latest
          imagePullPolicy: IfNotPresent   # ★ 로컬 이미지를 쓰려면 필수 (01 참고)

          ports:
            - name: http
              containerPort: 8080

          envFrom:
            - configMapRef: { name: coupon-config }
            - secretRef:    { name: coupon-secret }

          # ---- Probe 3종 (자세한 설명은 05) ----
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: 5
            failureThreshold: 30      # 최대 150초 기동 대기

          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3

          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3

          # ---- 리소스 ----
          resources:
            requests:
              cpu: "500m"
              memory: "768Mi"
            limits:
              memory: "1Gi"          # CPU limit 은 일부러 안 건다 (아래 설명)

          # ---- 종료 시 트래픽 빠질 시간 벌기 ----
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 5"]

---
apiVersion: v1
kind: Service
metadata:
  name: coupon-service
  namespace: coupon
  labels:
    app: coupon-service
spec:
  type: NodePort
  selector:
    app: coupon-service            # ★ 이 라벨을 가진 Pod 으로 부하분산
  ports:
    - name: http
      port: 8080
      targetPort: http
      nodePort: 30080              # kind-cluster.yaml 의 hostPort 8080 과 연결됨
```

## 2) 하나씩 뜯어보기

### `selector` ↔ `labels` — k8s 를 관통하는 원리

```yaml
# Deployment 가 관리할 Pod
selector:
  matchLabels: { app: coupon-service }
...
# Pod 에 붙는 라벨
template:
  metadata:
    labels: { app: coupon-service }
```

```yaml
# Service 가 트래픽을 보낼 Pod
selector: { app: coupon-service }
```

쿠버네티스에는 "이 Deployment 의 Service" 같은 **직접 참조가 없다.**
전부 **라벨 매칭**으로 느슨하게 연결된다. 그래서 Service 는 Deployment 를 몰라도 되고,
나중에 카나리 배포처럼 서로 다른 Deployment 의 Pod 을 한 Service 에 묶는 것도 가능하다.

라벨 오타는 조용히 실패한다. `Endpoints` 가 비면 이걸 의심하자.

```bash
kubectl -n coupon get endpoints coupon-service
```

### `maxUnavailable: 0` 이 무중단의 핵심

```
maxSurge: 1, maxUnavailable: 0
  → 새 Pod 1개 생성 → Ready 대기 → 옛 Pod 1개 종료 → 반복
  → 항상 Ready 인 Pod 이 3개 이상 유지된다
```

`maxUnavailable: 1` 이면 옛 Pod 을 먼저 내리므로 순간적으로 용량이 준다.
트래픽이 몰리는 서비스면 `0` 이 안전하다. 대신 노드 여유가 조금 더 필요하다.

### `preStop: sleep 5` 가 필요한 이유 ⭐

Pod 이 종료될 때 두 가지 일이 **동시에, 비동기로** 일어난다.

```
1. kubelet 이 컨테이너에 SIGTERM 을 보낸다
2. endpoints controller 가 Service 에서 이 Pod 을 뺀다  ← 전파에 시간이 걸림
```

2번이 모든 노드의 kube-proxy 까지 퍼지는 데 수백 ms ~ 수 초가 걸린다.
그 사이 1번이 먼저 끝나버리면 **아직 이 Pod 으로 오는 트래픽이 커넥션 거부를 맞는다.**

`preStop` 훅은 SIGTERM **전에** 실행되므로, 5초를 벌어 엔드포인트 제거가 전파되게 한다.
`sleep` 하나로 배포 중 502 를 없앨 수 있는, 가성비 최고의 설정이다.

전체 종료 흐름:

```
preStop(5s) → SIGTERM → graceful shutdown(최대 20s) → 그래도 안 죽으면 SIGKILL(40s 시점)
```

`terminationGracePeriodSeconds(40) > preStop(5) + timeout-per-shutdown-phase(20)` 이 성립해야 한다.

### `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70`

컨테이너 메모리 limit 이 1Gi 인데 JVM 이 그걸 모르고 힙을 크게 잡으면
**OOMKilled** 로 죽는다 (JVM 의 OutOfMemoryError 가 아니라 커널이 프로세스를 죽이는 것).

최신 JVM 은 cgroup limit 을 읽지만, 기본 비율(25%)이 보수적이라 메모리를 낭비한다.
`MaxRAMPercentage=70` 은 힙에 70%(약 700Mi)를 주고 나머지 30%를 메타스페이스,
스레드 스택, 다이렉트 버퍼, JVM 자체에 남긴다. **이 여유분을 안 남기면 반드시 OOMKilled 를 만난다.**

확인:

```bash
kubectl -n coupon exec deploy/coupon-service -- \
  java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -i maxheapsize
```

`OOMKilled` 를 만났다면:

```bash
kubectl -n coupon describe pod <pod> | grep -A5 'Last State'
```

### CPU limit 을 안 건 이유

CPU `requests` 는 **스케줄링 보장치**이고, `limits` 는 **cgroup 스로틀링**이다.
CPU limit 을 걸면 quota 를 다 쓴 순간 100ms 단위로 강제로 멈춰 세운다.
JVM 처럼 스레드가 많은 워크로드는 이 스로틀링으로 **p99 지연이 크게 튄다.**

메모리는 압축 불가능한(incompressible) 자원이라 limit 이 필요하지만,
CPU 는 압축 가능해서 limit 없이 request 만 잘 잡는 편이 낫다는 게 요즘 중론이다.
다만 멀티테넌트 클러스터에서는 이웃 보호를 위해 걸기도 한다 — 트레이드오프다.

> **05 에서 HPA 를 쓰려면 `requests.cpu` 는 반드시 있어야 한다.**
> HPA 의 CPU 사용률 = 실제 사용량 / **request** 이기 때문이다.

### `NodePort` 를 쓴 이유

| 타입 | 설명 | 언제 |
|---|---|---|
| ClusterIP | 클러스터 내부 전용 (기본) | Pod 끼리 통신 |
| NodePort | 모든 노드의 30000~32767 포트를 연다 | 로컬 실습 |
| LoadBalancer | 클라우드 LB 를 프로비저닝 | 클라우드 운영 |
| Ingress | L7 라우팅 (경로/호스트 기반) | 운영에서 여러 서비스 |

kind 는 클라우드 LB 가 없으니 NodePort + `extraPortMappings` 조합이 가장 단순하다.
**`nodePort: 30080` 덕분에 `http://localhost:8080` 으로 접근된다 → 기존 부하 테스트 스크립트를 그대로 쓴다.**

## 3) 배포

```bash
kubectl apply -f k8s/base/app.yaml
kubectl -n coupon rollout status deployment/coupon-service
```

### 확인

```bash
kubectl -n coupon get pods -o wide
```

```
NAME                              READY   STATUS    RESTARTS   NODE
coupon-service-6f8c9d4b7c-2xk9p   1/1     Running   0          coupon-worker
coupon-service-6f8c9d4b7c-7mn3q   1/1     Running   0          coupon-worker2
coupon-service-6f8c9d4b7c-p4vzt   1/1     Running   0          coupon-worker
```

`NODE` 열을 보자. `topologySpreadConstraints` 덕분에 노드에 흩어져 있다.

```bash
kubectl -n coupon get endpoints coupon-service   # Pod IP 3개가 나와야 한다
```

### 실제로 호출

```bash
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8080/actuator/health/readiness | jq
```

```json
{
  "status": "UP",
  "components": {
    "db":    { "status": "UP", "details": { "database": "MySQL" } },
    "redis": { "status": "UP" }
  }
}
```

DB, Redis 가 둘 다 `UP` 이면 ConfigMap/Secret/DNS 가 전부 제대로 연결된 것이다.

### 기존 API 스크립트 그대로 돌려보기

```bash
./scripts/api.sh
```

포트 매핑 덕분에 **아무것도 고치지 않고** 동작한다.

### 부하가 진짜 3개 Pod 에 나뉘는지 확인

터미널 두 개를 쓴다.

```bash
# 터미널 A: 모든 Pod 로그를 라벨로 한 번에 본다
kubectl -n coupon logs -f -l app=coupon-service --prefix --max-log-requests=5
```

```bash
# 터미널 B
for i in $(seq 1 20); do curl -s localhost:8080/actuator/health > /dev/null; done
```

A 에 세 Pod 이름이 섞여서 찍히면 부하분산이 되는 것이다.

## 4) 안 될 때

```bash
kubectl -n coupon get pods
kubectl -n coupon describe pod <pod-name>     # 맨 아래 Events 를 볼 것
kubectl -n coupon logs <pod-name>
kubectl -n coupon logs <pod-name> --previous  # 재시작한 경우 이전 컨테이너 로그
```

| 증상 | 원인 | 조치 |
|---|---|---|
| `ImagePullBackOff` | `kind load` 누락 / `imagePullPolicy` | 01 다시 |
| `CrashLoopBackOff` | 앱 기동 실패 | `logs --previous` 로 스택트레이스 확인 |
| `Running` 인데 `0/1` | readiness 실패 | `describe` 의 Events, actuator 응답 확인 |
| `Pending` | 리소스 부족 / PVC 대기 | `describe` 의 `FailedScheduling` |
| `OOMKilled` | 메모리 limit 초과 | limit 상향 또는 `MaxRAMPercentage` 하향 |
| curl 이 연결 거부 | Service/포트 매핑 | `get endpoints` 로 Pod 이 붙었는지 확인 |

## 체크포인트

1. DB 를 liveness probe 에 넣으면 왜 위험한가?
2. `preStop: sleep 5` 를 지우면 롤링 업데이트 중 무슨 일이 생기나?
3. Service 의 `selector` 를 `app: coupon` (오타)으로 바꾸면 어떤 증상이 나오나? 어디서 확인하나?
4. 메모리 limit 1Gi 에 `-Xmx1g` 를 주면 왜 위험한가?

<details>
<summary>답</summary>

1. DB 순단 시 모든 Pod 이 동시에 재시작 → 커넥션 폭풍 → 회복 방해 → 연쇄 재시작.
   readiness 는 재시작 없이 트래픽만 빼므로 DB 회복 후 자동 복귀한다.
2. 엔드포인트 제거가 전파되기 전에 컨테이너가 죽어서, 종료 중인 Pod 으로 간 요청이
   커넥션 거부(502/504)를 맞는다. 배포할 때마다 에러율이 튄다.
3. Pod 은 멀쩡히 Running 인데 `curl` 이 실패한다. `kubectl get endpoints coupon-service` 가
   `<none>` 으로 나오는 걸로 확인한다. 이게 라벨 오타의 전형적인 증상이다.
4. 힙만 1Gi 인데 메타스페이스/스레드 스택/JVM 오버헤드가 그 위에 더 붙는다.
   총 RSS 가 limit 을 넘는 순간 커널이 `OOMKilled` 로 프로세스를 죽인다.
   JVM 의 `OutOfMemoryError` 와 달리 스택트레이스도 안 남는다.
</details>

---
다음: [05-probes-scaling.md](05-probes-scaling.md)
