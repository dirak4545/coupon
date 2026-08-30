# 01. 클러스터 만들기 + 로컬 이미지 올리기

## 왜

쿠버네티스를 배울 때 첫 벽은 "클러스터가 없다" 이다.
클라우드(EKS/GKE)는 돈과 IAM 이 걸리고, Docker Desktop 내장 k8s 는 노드가 1개라 **스케줄링 관련 개념을 실습할 수 없다.**

**kind**(Kubernetes IN Docker)는 도커 컨테이너 하나를 노드 하나로 쓴다.
멀티 노드 클러스터를 30초 만에 만들고 지울 수 있어서 학습에 가장 적합하다.

## 1) kind 클러스터 설정 파일

프로젝트 루트에 `kind-cluster.yaml` 을 만든다.

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: coupon

nodes:
  # 컨트롤 플레인: API 서버, 스케줄러, etcd 가 여기 산다.
  - role: control-plane
    extraPortMappings:
      # 호스트 8080 -> 노드 30080 (NodePort).
      # 이렇게 뚫어두면 기존 부하 테스트 스크립트(localhost:8080)를 고치지 않고 쓸 수 있다.
      - containerPort: 30080
        hostPort: 8080
        protocol: TCP
        listenAddress: "127.0.0.1"

  # 워커 2개: Pod 이 여러 노드에 흩어지는 걸 눈으로 보기 위함.
  - role: worker
  - role: worker
```

### 여기서 배울 점

- **`extraPortMappings`**: kind 노드는 도커 컨테이너다. 호스트에서 클러스터 안으로 들어가려면
  도커 포트 매핑이 필요하다. 실제 클라우드라면 이 자리에 LoadBalancer 나 Ingress 가 온다.
- **노드가 3개인 이유**: replica 3개가 서로 다른 노드에 흩어지는 걸 봐야
  "분산되어 있다"는 게 실감난다. 05 의 `topologySpreadConstraints` 도 노드가 여럿이어야 의미가 있다.
- 노트북이 버거우면 `worker` 를 하나만 남겨도 실습은 전부 된다.

## 2) 클러스터 생성

```bash
kind create cluster --config kind-cluster.yaml
```

1~2분 걸린다. 끝나면 kubectl 컨텍스트가 `kind-coupon` 으로 자동 전환된다.

### 확인

```bash
kubectl config current-context
kubectl get nodes -o wide
```

```
NAME                   STATUS   ROLES           AGE   VERSION
coupon-control-plane   Ready    control-plane   68s   v1.3x.x
coupon-worker          Ready    <none>          52s   v1.3x.x
coupon-worker2         Ready    <none>          52s   v1.3x.x
```

3개 다 `Ready` 여야 한다. 도커에서도 확인해보자 — 노드가 그냥 컨테이너다.

```bash
docker ps --filter name=coupon --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
```

### 클러스터 안에 뭐가 이미 돌고 있나

```bash
kubectl get pods -A
```

`kube-system` 네임스페이스에 CoreDNS, kube-proxy, etcd, scheduler 등이 보인다.
**CoreDNS** 는 뒤에서 `mysql` 같은 이름을 IP 로 바꿔줄 놈이라 기억해두자.

## 3) 앱 이미지 빌드

이 프로젝트는 Dockerfile 이 없다. **Jib** 이 Gradle 에서 바로 이미지를 만든다.

```bash
./gradlew clean jibDockerBuild
```

`jibDockerBuild` 는 로컬 **도커 데몬**에 `coupon-service:latest` 를 만든다.

```bash
docker images coupon-service
```

## 4) 이미지를 kind 클러스터에 로드 ⭐

**가장 많이 틀리는 지점이다.**

kind 노드는 **격리된 도커 컨테이너**이고, 자기만의 컨테이너 런타임(containerd)을 가진다.
호스트 도커 데몬에 있는 이미지를 **볼 수 없다.**

```bash
kind load docker-image coupon-service:latest --name coupon
```

이 명령이 호스트 도커에서 이미지를 뽑아 각 노드의 containerd 로 밀어넣는다.

### 확인

```bash
docker exec coupon-control-plane crictl images | grep coupon
docker exec coupon-worker crictl images | grep coupon
```

### 짝꿍 함정: `imagePullPolicy`

이미지 태그가 `:latest` 면 쿠버네티스의 기본 `imagePullPolicy` 는 **`Always`** 다.
그러면 로컬에 이미지가 있어도 레지스트리에서 받으려 하고, Docker Hub 에 `coupon-service` 는 없으니 **`ErrImagePull`** 로 죽는다.

그래서 04 의 Deployment 에는 반드시 이렇게 쓴다.

```yaml
imagePullPolicy: IfNotPresent
```

> **기억할 규칙**: 코드를 고칠 때마다 `jibDockerBuild` **+** `kind load` **둘 다** 해야 한다.
> 하나만 하면 옛날 이미지가 계속 돈다. 이걸 몰라서 "코드를 고쳤는데 반영이 안 돼요" 로 몇 시간 날린다.

### 매번 치기 귀찮으면

`scripts/k8s-reload.sh` 로 만들어두자.

```bash
#!/usr/bin/env bash
# 이미지 재빌드 -> kind 로드 -> 앱 롤아웃 재시작
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew jibDockerBuild
kind load docker-image coupon-service:latest --name coupon
kubectl -n coupon rollout restart deployment/coupon-service 2>/dev/null || true
kubectl -n coupon rollout status  deployment/coupon-service 2>/dev/null || true
```

```bash
chmod +x scripts/k8s-reload.sh
```

## 체크포인트

1. `kind load` 를 빼먹으면 Pod 상태가 뭐가 되나? 그 원인은 어디서 확인하나?
2. `imagePullPolicy` 를 안 적으면 `:latest` 태그에서 왜 실패하나?
3. 노드 3개인데 `kubectl get nodes` 로 보면 왜 `docker ps` 결과와 개수가 같나?

<details>
<summary>답</summary>

1. `ErrImagePull` → `ImagePullBackOff`. `kubectl describe pod <name>` 의 Events 섹션에서 확인.
2. `:latest` 는 "내용이 바뀔 수 있는 태그"로 취급돼 기본 정책이 `Always`. 매번 레지스트리를 조회하는데 그런 이미지가 없어서 실패.
3. kind 는 노드 = 도커 컨테이너이기 때문. k8s 노드가 물리/가상 머신이어야 한다는 법은 없다.
</details>

## 정리 (필요할 때)

```bash
kind delete cluster --name coupon
```

---
다음: [02-config.md](02-config.md)
