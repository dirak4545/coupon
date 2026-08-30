# 02. Namespace, ConfigMap, Secret

## 왜

지금 `docker-compose.yaml` 은 이렇게 돼 있다.

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/coupon
  SPRING_DATASOURCE_PASSWORD: coupon   # ← 평문 비밀번호가 git 에 들어감
```

문제 두 가지.

1. **설정과 비밀이 섞여 있다.** DB URL 은 공개돼도 되지만 비밀번호는 아니다.
2. **환경별로 파일을 통째로 복사해야 한다.** dev/stage/prod 마다 compose 파일이 늘어난다.

k8s 는 이걸 **ConfigMap**(설정)과 **Secret**(비밀)으로 나눈다.
이미지는 그대로 두고 주입값만 바꾸면 되니, "**한 번 빌드, 여러 환경 배포**"가 된다.

`application.yaml` 이 이미 `${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/coupon}` 처럼
**환경변수 우선, 없으면 로컬 기본값** 구조라서 코드 수정 없이 그대로 먹는다. 잘 짜여 있다.

## 1) Namespace

`k8s/base/namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: coupon
  labels:
    app.kubernetes.io/part-of: coupon-service
```

### 왜 필요한가

Namespace 는 **오브젝트 이름의 유효 범위**다. 같은 클러스터 안에서 `dev/mysql` 과 `prod/mysql` 이 공존할 수 있다.
덤으로:

- **정리가 쉽다**: `kubectl delete namespace coupon` 한 방에 전부 사라진다
- **DNS 에 들어간다**: `mysql.coupon.svc.cluster.local` 의 가운데 `coupon` 이 이 이름
- ResourceQuota / NetworkPolicy / RBAC 의 기본 적용 단위다

매번 `-n coupon` 치기 귀찮으면 기본값을 바꾼다.

```bash
kubectl config set-context --current --namespace=coupon
```

> 이 문서들에서는 헷갈리지 않게 항상 `-n coupon` 을 명시한다.

## 2) ConfigMap

`k8s/base/configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: coupon-config
  namespace: coupon
data:
  # 값은 반드시 문자열. 숫자도 따옴표로 감싼다.
  SPRING_DATASOURCE_URL: "jdbc:mysql://mysql:3306/coupon?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  SPRING_DATASOURCE_USERNAME: "coupon"

  SPRING_DATA_REDIS_HOST: "redis"
  SPRING_DATA_REDIS_PORT: "6379"

  SPRING_PROFILES_ACTIVE: "k8s"

  # 컨테이너 메모리 한도를 JVM 이 인식하게 한다. 04 에서 다시 설명.
  JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50"
```

### 여기서 배울 점

**`mysql` 이라는 호스트 이름은 어디서 오나?**
03 에서 만들 Service 의 `metadata.name` 이다. CoreDNS 가 이걸 ClusterIP 로 해석한다.
같은 네임스페이스면 짧은 이름(`mysql`)으로 되고, 다른 네임스페이스면 `mysql.coupon` 또는
FQDN `mysql.coupon.svc.cluster.local` 이 필요하다.

**`ConfigMap` 의 `data` 값은 전부 문자열이어야 한다.**
`SPRING_DATA_REDIS_PORT: 6379` 라고 쓰면 YAML 파서가 정수로 읽어서 apply 가 거부된다.
따옴표를 빼먹는 실수가 정말 흔하다.

**`allowPublicKeyRetrieval=true`**: MySQL 8 의 기본 인증(`caching_sha2_password`)이
비 TLS 연결에서 공개키를 요구한다. 학습용이라 켰지만, **운영에서는 TLS 를 켜고 이 옵션을 빼야 한다.**
이 옵션은 중간자 공격에 노출될 수 있다.

## 3) Secret

`k8s/base/secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: coupon-secret
  namespace: coupon
type: Opaque
# stringData: 평문으로 쓰면 k8s 가 저장 시 base64 로 바꿔준다.
# data: 를 쓰면 직접 base64 인코딩해서 넣어야 한다.
stringData:
  MYSQL_ROOT_PASSWORD: "root"
  MYSQL_DATABASE: "coupon"
  MYSQL_USER: "coupon"
  MYSQL_PASSWORD: "coupon"
  SPRING_DATASOURCE_PASSWORD: "coupon"
```

### ⚠️ Secret 에 대한 오해 바로잡기

**Secret 은 암호화가 아니다. base64 인코딩일 뿐이다.**

```bash
kubectl -n coupon get secret coupon-secret -o jsonpath='{.data.MYSQL_PASSWORD}' | base64 -d
# coupon   ← 누구나 읽는다
```

base64 는 인코딩이지 암호화가 아니다. 이 파일을 그대로 git 에 커밋하면 평문 커밋과 다를 게 없다.
ConfigMap 과 분리하는 실익은:

- **RBAC 로 Secret 만 따로 막을 수 있다** (ConfigMap 은 읽되 Secret 은 못 읽게)
- `kubectl describe` 가 값을 가려준다 (로그·스크린샷 사고 방지)
- **etcd 저장 시 암호화**(EncryptionConfiguration)를 켤 수 있다
- 외부 비밀 관리자(External Secrets Operator, Vault, AWS Secrets Manager)의 연결 지점이 된다

실습이니 그냥 커밋하지만, **운영에서는 절대 이러면 안 된다.** 07 에서 대안을 다룬다.

### 커밋 없이 만드는 방법 (권장 습관)

```bash
kubectl -n coupon create secret generic coupon-secret \
  --from-literal=MYSQL_ROOT_PASSWORD=root \
  --from-literal=MYSQL_DATABASE=coupon \
  --from-literal=MYSQL_USER=coupon \
  --from-literal=MYSQL_PASSWORD=coupon \
  --from-literal=SPRING_DATASOURCE_PASSWORD=coupon
```

## 4) 적용

```bash
kubectl apply -f k8s/base/namespace.yaml
kubectl apply -f k8s/base/configmap.yaml
kubectl apply -f k8s/base/secret.yaml
```

### 확인

```bash
kubectl -n coupon get configmap,secret
kubectl -n coupon describe configmap coupon-config
kubectl -n coupon get secret coupon-secret -o yaml   # 값이 base64 로 보인다
```

## 5) 주입 방식 3가지

04 에서 Deployment 에 쓸 텐데, 차이를 알아두자.

**A. `envFrom` — 통째로 환경변수로** (이 프로젝트에서 쓸 방식)

```yaml
envFrom:
  - configMapRef:
      name: coupon-config
  - secretRef:
      name: coupon-secret
```

`application.yaml` 이 이미 환경변수 기반이라 이게 가장 잘 맞는다.

**B. `env` — 키 하나씩 골라서**

```yaml
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: coupon-secret
        key: SPRING_DATASOURCE_PASSWORD
```

이름을 바꿔 매핑할 때 유용하다. MySQL StatefulSet 에서 이 방식을 쓴다.

**C. 볼륨 마운트 — 파일로**

```yaml
volumeMounts:
  - name: config
    mountPath: /config
```

`application-k8s.yaml` 을 통째로 넣거나 인증서를 넣을 때 쓴다.
**환경변수와 달리 ConfigMap 을 수정하면 파일 내용이 (몇십 초 뒤) 자동 갱신된다.**
반면 환경변수는 Pod 을 재시작해야만 반영된다 — 이게 A 와 C 의 결정적 차이다.

## 체크포인트

1. ConfigMap 값을 바꾸고 `kubectl apply` 했다. `envFrom` 으로 주입한 앱에 언제 반영되나?
2. Secret 을 git 에 커밋하면 안 되는 이유를 "base64 는 암호화가 아니다" 외에 한 가지 더 대보자.
3. `SPRING_DATA_REDIS_PORT: 6379` (따옴표 없음) 로 쓰면 어떻게 되나?

<details>
<summary>답</summary>

1. **반영 안 된다.** Pod 을 재시작해야 한다(`kubectl rollout restart deployment/coupon-service`).
   환경변수는 프로세스 시작 시점에 고정된다. 볼륨 마운트 방식만 자동 갱신된다.
2. git 히스토리는 영구적이다 — 나중에 지워도 과거 커밋에 남는다. 유출 시 비밀번호 교체(rotation)만이 답이고,
   그것도 이 값을 참조하는 모든 곳을 찾아야 한다.
3. `cannot unmarshal number into Go struct field ... of type string` 류의 오류로 apply 가 거부된다.
   ConfigMap `data` 의 값은 반드시 문자열이다.
</details>

---
다음: [03-datastore.md](03-datastore.md)
