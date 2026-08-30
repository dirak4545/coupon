# 03. MySQL, Redis — StatefulSet, PVC, Headless Service

## 왜 Deployment 가 아니라 StatefulSet 인가

Deployment 는 Pod 을 **서로 대체 가능한 소모품**으로 본다.
이름이 `coupon-service-7d4b9c-x8k2p` 처럼 랜덤이고, 죽으면 새 이름으로 다시 뜨고, 볼륨을 공유한다.

데이터베이스는 그러면 안 된다.

| 필요 | Deployment | StatefulSet |
|---|---|---|
| 안정적인 이름 | ❌ 랜덤 접미사 | ✅ `mysql-0`, `mysql-1` |
| Pod 별 고유 볼륨 | ❌ 전부 같은 PVC 공유 | ✅ `volumeClaimTemplates` 로 하나씩 |
| 순서 보장 | ❌ 동시에 생성/삭제 | ✅ 0 → 1 → 2 순차 |
| 개별 DNS | ❌ | ✅ `mysql-0.mysql.coupon.svc.cluster.local` |

이 프로젝트는 MySQL 이 1개뿐이라 사실 Deployment + PVC 로도 굴러간다.
그럼에도 StatefulSet 을 쓰는 이유는 **나중에 복제(replication)로 확장할 때 구조를 안 갈아엎기 위해서**,
그리고 **"상태 있는 것 = StatefulSet" 이라는 습관을 들이기 위해서**다.

> **현실 조언**: 운영 환경에서 DB 를 직접 k8s 에 올리는 건 난이도가 높다.
> 백업, 장애 복구, 버전 업그레이드가 전부 숙제가 된다. 실무에서는 RDS/Cloud SQL 같은 관리형을 쓰거나,
> 최소한 오퍼레이터(Percona, Vitess, MySQL Operator)를 쓴다.
> 여기서는 **학습 목적**으로 직접 올린다.

## 1) MySQL

`k8s/base/mysql.yaml`

```yaml
# ---------- Headless Service ----------
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: coupon
  labels:
    app: mysql
spec:
  # clusterIP: None -> Headless Service.
  # 가상 IP 를 만들지 않고 DNS 가 Pod IP 를 직접 돌려준다.
  # StatefulSet 은 Pod 별 고유 주소가 필요해서 보통 headless 를 쓴다.
  clusterIP: None
  selector:
    app: mysql
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
---
# ---------- StatefulSet ----------
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
  namespace: coupon
spec:
  serviceName: mysql          # 위 headless Service 이름과 일치해야 개별 DNS 가 생긴다
  replicas: 1
  selector:
    matchLabels:
      app: mysql
  template:
    metadata:
      labels:
        app: mysql
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: mysql
          image: mysql:8.4
          args:
            - --character-set-server=utf8mb4
            - --collation-server=utf8mb4_0900_ai_ci
          ports:
            - name: mysql
              containerPort: 3306

          env:
            # Secret 의 키를 컨테이너가 기대하는 이름으로 매핑.
            - name: MYSQL_ROOT_PASSWORD
              valueFrom:
                secretKeyRef: { name: coupon-secret, key: MYSQL_ROOT_PASSWORD }
            - name: MYSQL_DATABASE
              valueFrom:
                secretKeyRef: { name: coupon-secret, key: MYSQL_DATABASE }
            - name: MYSQL_USER
              valueFrom:
                secretKeyRef: { name: coupon-secret, key: MYSQL_USER }
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef: { name: coupon-secret, key: MYSQL_PASSWORD }

          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql

          # 첫 기동은 DB 초기화 때문에 오래 걸린다.
          # startupProbe 가 통과하기 전까지 liveness/readiness 는 아예 실행되지 않는다.
          startupProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h 127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            periodSeconds: 5
            failureThreshold: 60      # 최대 5분까지 기다려준다

          readinessProbe:
            exec:
              command: ["sh", "-c", "mysql -h 127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\" -e 'SELECT 1'"]
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3

          livenessProbe:
            exec:
              command: ["sh", "-c", "mysqladmin ping -h 127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\""]
            periodSeconds: 20
            timeoutSeconds: 5
            failureThreshold: 5       # DB 재시작은 비싸다. 넉넉하게.

          resources:
            requests: { cpu: "250m", memory: "512Mi" }
            limits:   { memory: "1Gi" }

  # ---------- Pod 마다 PVC 를 하나씩 만들어준다 ----------
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 5Gi
```

### 여기서 배울 점

**Headless Service (`clusterIP: None`)**
일반 Service 는 가상 IP(ClusterIP)를 만들고 그 뒤에서 부하분산한다.
Headless 는 IP 를 안 만들고 **DNS 조회에 Pod IP 목록을 그대로 반환**한다.
그 대신 `<pod>.<service>.<ns>.svc.cluster.local` 형태의 개별 주소가 생긴다.
DB 복제에서 "쓰기는 무조건 `mysql-0`" 같은 라우팅을 하려면 이게 필요하다.

**`volumeClaimTemplates` vs `volumes`**
`volumes` 로 PVC 를 붙이면 모든 Pod 이 **같은** 볼륨을 본다 (DB 에는 치명적).
`volumeClaimTemplates` 는 Pod 마다 **별도** PVC 를 만든다: `data-mysql-0`, `data-mysql-1`...

**PVC 는 StatefulSet 을 지워도 안 지워진다.** 이건 의도된 안전장치다.

```bash
kubectl -n coupon delete statefulset mysql
kubectl -n coupon get pvc          # data-mysql-0 는 그대로 살아있다
```

다시 만들면 데이터가 붙어서 돌아온다. 진짜로 지우려면 PVC 를 직접 지워야 한다.

**`accessModes: ReadWriteOnce`**
"노드 하나에서만 읽기/쓰기 가능". kind 의 기본 StorageClass 인 `local-path` 는
**노드의 로컬 디스크**를 쓴다. 즉 Pod 이 다른 노드로 옮겨가면 데이터가 안 따라온다.
클라우드의 EBS/PD 는 노드 간 이동이 되지만, 그래도 존(zone)에 묶인다.
**스토리지는 위치에 묶인다** — 이게 상태 있는 워크로드가 어려운 근본 이유다.

**`startupProbe` 를 왜 따로 두나**
`livenessProbe` 만 있으면 "기동이 느린 것"과 "죽은 것"을 구분할 수 없다.
느린 기동을 감당하려고 liveness 의 `failureThreshold` 를 크게 잡으면,
정작 운영 중에 진짜 멈췄을 때도 그만큼 늦게 감지된다.
`startupProbe` 는 이 둘을 분리한다: 기동 중엔 넉넉하게, 기동 후엔 빡빡하게.

## 2) Redis

`k8s/base/redis.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: coupon
  labels:
    app: redis
spec:
  clusterIP: None
  selector:
    app: redis
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  namespace: coupon
spec:
  serviceName: redis
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: redis
          image: redis:8.0
          # docker-compose 의 command 와 동일. AOF 로 재시작 시 데이터를 복구한다.
          args: ["redis-server", "--appendonly", "yes", "--dir", "/data"]
          ports:
            - name: redis
              containerPort: 6379
          volumeMounts:
            - name: data
              mountPath: /data
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            periodSeconds: 5
            timeoutSeconds: 3
          livenessProbe:
            exec:
              command: ["redis-cli", "ping"]
            periodSeconds: 15
            timeoutSeconds: 3
            failureThreshold: 5
          resources:
            requests: { cpu: "100m", memory: "128Mi" }
            limits:   { memory: "512Mi" }
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 1Gi
```

### ⚠️ 이 프로젝트에서 Redis 가 특별히 중요한 이유

`CouponIssuer` 를 다시 보자.

```kotlin
fun tryIssue(couponId: Long) { ... }        // Lua 로 coupon:{id}:stock 을 원자적으로 감소
fun initStock(couponId: Long, totalQuantity: Int) { ... }  // 쿠폰 생성 시 1회만 세팅
```

**재고의 단일 진실 공급원(source of truth)이 Redis 다.**
그리고 `initStock` 은 **쿠폰 생성 시점에 딱 한 번만** 호출된다.

여기서 나오는 것이 쿠버네티스가 가르쳐주는 아주 값진 교훈이다.

> **Redis Pod 이 재시작해서 `coupon:1:stock` 키가 사라지면?**
>
> Lua 스크립트가 `GET` 결과를 `or '0'` 으로 처리한다 → `remaining = 0` → **전부 SoldOut**.
> DB 에는 재고가 4,000장 남아 있는데 사용자에게는 전부 매진으로 보인다.
> 앱은 정상이고, 로그에 에러도 없고, 헬스체크도 통과한다. **조용히 망가진다.**

k8s 에서 Pod 재시작은 예외 상황이 아니라 **일상**이다. 노드 교체, 스케일 조정, OOM,
노드 드레인 — 언제든 일어난다. compose 에서는 잘 안 겪던 일이라 놓치기 쉽다.

이걸 직접 만들어보는 게 이 문서의 하이라이트다. 06 에서 재현한다.

방어 방법(이 브랜치의 범위 밖이지만 알아둘 것):
- AOF + PVC (지금 한 것) — 재시작은 버텨도 볼륨 유실은 못 버틴다
- 앱 기동 시 DB 기준으로 Redis 재고를 재계산해서 채우는 **워밍업 로직**
- 미스 시 DB 로 폴백(fallback)
- Redis Sentinel / Cluster 로 고가용성 확보

## 3) 적용

```bash
kubectl apply -f k8s/base/mysql.yaml
kubectl apply -f k8s/base/redis.yaml
```

### 뜨는 과정 관찰하기

```bash
kubectl -n coupon get pods -w
```

`Pending` → `ContainerCreating` → `Running` 순서로 바뀐다.
`Pending` 에서 오래 멈춰 있으면 대개 **PVC 바인딩 대기**나 **스케줄 실패**다.

```bash
kubectl -n coupon get pvc
kubectl -n coupon describe pod mysql-0 | tail -30
```

### 확인

```bash
kubectl -n coupon get statefulset,pod,pvc,svc
```

DB 에 직접 붙어보자.

```bash
kubectl -n coupon exec -it mysql-0 -- mysql -ucoupon -pcoupon coupon -e "SHOW TABLES;"
kubectl -n coupon exec -it redis-0 -- redis-cli PING
```

MySQL 테이블은 아직 비어 있다 — 앱이 뜨면서 Hibernate `ddl-auto: update` 가 만든다.

### DNS 가 진짜 되는지 확인 (아주 유익함)

```bash
kubectl -n coupon run netshoot --rm -it --restart=Never \
  --image=nicolaka/netshoot -- nslookup mysql.coupon.svc.cluster.local
```

```
Name: mysql.coupon.svc.cluster.local
Address: 10.244.1.5      ← Pod IP 가 그대로 나온다 (headless 니까)
```

일반(ClusterIP) Service 였다면 여기 가상 IP 가 나온다. 차이를 눈으로 확인해두자.

## 4) 데이터가 진짜 살아남는지 실험

```bash
kubectl -n coupon exec redis-0 -- redis-cli SET test:key hello
kubectl -n coupon delete pod redis-0          # 강제로 죽인다
kubectl -n coupon wait --for=condition=ready pod/redis-0 --timeout=120s
kubectl -n coupon exec redis-0 -- redis-cli GET test:key
# "hello"  ← AOF + PVC 덕분에 살아남았다
kubectl -n coupon exec redis-0 -- redis-cli DEL test:key
```

이제 PVC 를 빼면 어떻게 되는지도 머릿속으로 그려보자. (`emptyDir` 였다면 사라진다)

## 체크포인트

1. Headless Service 와 일반 ClusterIP Service 의 DNS 응답이 어떻게 다른가?
2. `kubectl delete statefulset mysql` 후 다시 apply 하면 데이터가 남아 있나? 왜?
3. Redis 데이터가 유실되면 이 서비스에 정확히 무슨 일이 생기나? 에러 로그가 남나?
4. `startupProbe` 없이 `livenessProbe` 만 두면 MySQL 첫 기동 때 무슨 일이 생길 수 있나?

<details>
<summary>답</summary>

1. Headless 는 Pod IP 목록을 직접 반환하고 개별 Pod DNS(`mysql-0.mysql...`)를 만든다.
   ClusterIP 는 가상 IP 하나를 반환하고 kube-proxy 가 그 뒤에서 부하분산한다.
2. **남아 있다.** PVC 는 StatefulSet 의 생명주기와 분리돼 있고 이름(`data-mysql-0`)이 결정적이라
   같은 이름의 StatefulSet 이 다시 뜨면 그대로 재결합한다.
3. 모든 발급이 `SoldOutException` (매진)으로 응답한다. DB 재고와 무관하게.
   **에러 로그는 안 남는다** — 앱 입장에서 정상적인 매진 처리이기 때문. 이게 제일 무섭다.
4. liveness 실패가 누적돼 기동이 끝나기도 전에 컨테이너가 재시작된다.
   재시작 → 다시 초기화 → 또 실패의 무한 `CrashLoopBackOff` 에 빠질 수 있다.
</details>

---
다음: [04-app.md](04-app.md)
