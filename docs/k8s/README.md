# 쿠폰 서비스 쿠버네티스 학습 자료

`part-2-kub` 브랜치용 자료. `part-2-3`(Redis Lua 원자 발급) 코드를 그대로 두고,
**실행 환경만 docker compose → 쿠버네티스로 옮긴다.**

## 이 브랜치의 질문

part-2-3 에서 이렇게 정리했다.

> 재고 차감을 Redis Lua 로 옮겨서 원자적으로 만들었다. 초과발급이 사라졌다.

그런데 그때 앱은 **컨테이너 1개**였다. 그러면 사실 이런 반박이 가능하다.

> "인스턴스가 하나뿐인데, 그 안에서 원자적인 게 뭐가 대단한가?
>  JVM 내부 `synchronized` 로도 막혔을 텐데?"

이 브랜치는 그 반박에 답한다.

- 앱 Pod 을 **3개**로 띄운다 (JVM 3개, 서로 메모리 공유 없음)
- 같은 부하(`over_issuance.js`)를 그대로 건다
- **여전히 정확히 5,000장**이면, 원자성이 프로세스가 아니라 **Redis**에 있다는 증거다

즉 쿠버네티스는 여기서 "배포 도구"가 아니라 **분산 정합성 검증 장비**로 쓰인다.
`kubectl scale --replicas=N` 이 실험 변수를 바꾸는 손잡이다.

## 학습 순서

| 단계 | 문서 | 배우는 것 |
|---|---|---|
| 0 | 이 문서 | compose ↔ k8s 개념 매핑 |
| 1 | [01-cluster.md](01-cluster.md) | kind 클러스터, 로컬 이미지 로드 |
| 2 | [02-config.md](02-config.md) | Namespace, ConfigMap, Secret |
| 3 | [03-datastore.md](03-datastore.md) | StatefulSet, PVC, Headless Service (MySQL·Redis) |
| 4 | [04-app.md](04-app.md) | Actuator 추가, Deployment, Service, 첫 배포 |
| 5 | [05-probes-scaling.md](05-probes-scaling.md) | Probe 3종, 롤링 업데이트, HPA, PDB |
| 6 | [06-loadtest.md](06-loadtest.md) | **replica 3으로 초과발급 재검증** (핵심) |
| 7 | [07-kustomize.md](07-kustomize.md) | base/overlay 로 환경 분리 |
| - | [99-cheatsheet.md](99-cheatsheet.md) | kubectl 디버깅 명령 모음 |

각 문서는 `왜 → 작성 → 적용 → 확인 → 체크포인트` 순서다.
**체크포인트 질문에 답하지 못하면 다음 문서로 넘어가지 말 것.** 복붙만 하면 남는 게 없다.

## 개념 매핑

지금 `docker-compose.yaml` 이 하는 일이 k8s 에서 무엇으로 쪼개지는지부터 잡고 가자.
쿠버네티스가 어려운 이유의 절반은 **compose 의 한 줄이 k8s 에서 여러 오브젝트로 나뉘기 때문**이다.

| docker-compose | 쿠버네티스 | 왜 나뉘었나 |
|---|---|---|
| `services.coupon-service` | **Deployment** | "몇 개를 어떤 이미지로 굴릴지"(원하는 상태)만 선언 |
| 컨테이너 실행 단위 | **Pod** | k8s 의 최소 배포 단위. 컨테이너 1+개 + 네트워크 네임스페이스 |
| `ports: 8080:8080` | **Service** | Pod IP 는 계속 바뀌므로, 고정 이름/IP 가 앞에 필요 |
| 서비스명으로 접속(`mysql:3306`) | **Service + CoreDNS** | `mysql.coupon.svc.cluster.local` 로 해석 |
| `environment:` | **ConfigMap** | 설정을 이미지에서 분리 → 같은 이미지로 여러 환경 |
| 비밀번호 평문 | **Secret** | 별도 오브젝트 + RBAC 로 접근 제어 (암호화는 아님, 03 참고) |
| `volumes: mysql-data` | **PVC + StorageClass** | 스토리지를 "요청"하면 클러스터가 붙여줌 |
| `depends_on: condition: service_healthy` | **Probe + 재시도** | k8s 는 기동 순서를 보장하지 않는다. 대신 **계속 재시도**한다 |
| `healthcheck:` | **liveness / readiness / startup** | 3가지로 분리됨 (05 참고) |
| `docker compose up -d` | `kubectl apply -f` | 명령형 → **선언형**. "이 상태로 만들어라" |
| 상태 유지 컨테이너 | **StatefulSet** | 안정적 이름(`mysql-0`) + Pod 별 고정 볼륨 |
| - | **Namespace** | 오브젝트 논리 분리. 여기선 전부 `coupon` 에 넣는다 |

### 가장 중요한 사고방식 전환

compose 는 **명령형**이다. "컨테이너를 띄워라."
k8s 는 **선언형**이다. "replica 3개인 상태를 유지해라."

그래서 k8s 에서는 이런 일이 자동으로 일어난다.

- Pod 이 죽으면 → 컨트롤러가 알아서 새로 만든다
- 노드가 죽으면 → 다른 노드에 다시 스케줄한다
- 이미지 태그를 바꾸면 → 롤링 업데이트가 알아서 굴러간다

`depends_on` 이 없는 이유도 여기 있다. MySQL 이 아직 안 떴으면 앱 Pod 은 **실패하고, 재시작하고, 또 시도한다.**
"순서를 맞추는" 대신 "될 때까지 시도하는" 모델이다. 이건 버그가 아니라 설계다.

## 사전 준비

```bash
brew install kind kubectl kustomize k6 jq
```

Docker Desktop 이 켜져 있어야 한다(kind 는 도커 컨테이너를 노드로 쓴다).

```bash
kind --version && kubectl version --client && docker info | grep -i 'server version'
```

> **Apple Silicon 주의**: `build.gradle.kts` 의 jib 설정이 `arm64` 로 고정돼 있다.
> kind 노드도 arm64 라 그대로 맞는다. Intel Mac 이면 jib `platform` 을 `amd64` 로 바꿔야 한다.

## 최종 결과물

```
k8s/
├── base/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── mysql.yaml
│   ├── redis.yaml
│   ├── app.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   └── kustomization.yaml
└── overlays/
    ├── local/
    └── prod/
kind-cluster.yaml
```

전부 이 문서들을 따라가며 **직접 손으로 작성**한다.
