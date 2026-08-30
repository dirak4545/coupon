# 99. 치트시트

## 이 프로젝트 전용 흐름

```bash
# 클러스터 생성 (최초 1회)
kind create cluster --config kind-cluster.yaml

# 코드 수정 후 반영 (제일 자주 쓴다)
./gradlew jibDockerBuild
kind load docker-image coupon-service:latest --name coupon
kubectl -n coupon rollout restart deployment/coupon-service
kubectl -n coupon rollout status  deployment/coupon-service

# 전체 배포
kubectl apply -k k8s/overlays/local

# 부하 테스트 (k8s 대상)
RUNTIME=k8s ./scripts/load/part-2/run.sh

# replica 바꿔가며 실험
kubectl -n coupon scale deployment/coupon-service --replicas=3

# 전부 지우기
kind delete cluster --name coupon
```

## 상태 보기

```bash
kubectl -n coupon get all
kubectl -n coupon get pods -o wide              # 노드까지 표시
kubectl -n coupon get pods -w                   # 변화를 실시간으로
kubectl -n coupon get events --sort-by=.lastTimestamp | tail -30
kubectl -n coupon get pod <pod> -o yaml         # 실제 적용된 전체 스펙
kubectl -n coupon get endpoints coupon-service  # Service 에 붙은 Pod IP
```

## 디버깅 4단계

문제가 생기면 **항상 이 순서**로 좁힌다.

```bash
# 1. 상태가 뭔가?
kubectl -n coupon get pods

# 2. 왜 그 상태인가? (Events 를 볼 것 — 맨 아래)
kubectl -n coupon describe pod <pod>

# 3. 앱이 뭐라고 하나?
kubectl -n coupon logs <pod>
kubectl -n coupon logs <pod> --previous          # 재시작 전 로그 ★
kubectl -n coupon logs -l app=coupon-service -f --prefix --max-log-requests=5

# 4. 들어가서 직접 본다
kubectl -n coupon exec -it <pod> -- sh
```

`--previous` 를 모르면 `CrashLoopBackOff` 를 절대 못 고친다. 새 컨테이너는 아직 로그가 없으니까.

## Pod 상태별 원인

| 상태 | 뜻 | 먼저 볼 곳 |
|---|---|---|
| `Pending` | 스케줄 안 됨 | `describe` → `FailedScheduling` (리소스/PVC/affinity) |
| `ContainerCreating` | 이미지 pull, 볼륨 마운트 중 | `describe` Events |
| `ImagePullBackOff` | 이미지를 못 가져옴 | `kind load` 했나? `imagePullPolicy` 는? |
| `CrashLoopBackOff` | 계속 죽음 | `logs --previous` |
| `Running` + `0/1` | readiness 실패 | `describe` Events, actuator 응답 |
| `OOMKilled` | 메모리 limit 초과 | `describe` → `Last State`, JVM 힙 설정 |
| `Terminating` 지속 | finalizer / graceful 대기 | `describe`, `--grace-period=0 --force` |
| `Error` | 컨테이너가 0 아닌 코드로 종료 | `logs --previous` |

## 네트워크 확인

```bash
# 포트 포워딩 (NodePort 없이 접근)
kubectl -n coupon port-forward svc/coupon-service 8080:8080
kubectl -n coupon port-forward statefulset/mysql 3306:3306   # 로컬 DB 툴로 접속

# 클러스터 안에서 DNS/연결 확인
kubectl -n coupon run netshoot --rm -it --restart=Never --image=nicolaka/netshoot -- bash
  # 안에서:
  # nslookup mysql
  # nc -zv mysql 3306
  # curl coupon-service:8080/actuator/health

# 앱 Pod 에서 직접
kubectl -n coupon exec deploy/coupon-service -- curl -s localhost:8080/actuator/health
```

## 이 프로젝트 데이터 확인

```bash
# MySQL
kubectl -n coupon exec -it statefulset/mysql -- \
  env MYSQL_PWD=coupon mysql -ucoupon -t coupon \
  -e "SELECT id,name,total_quantity,issued_quantity FROM coupon;"

kubectl -n coupon exec -it statefulset/mysql -- \
  env MYSQL_PWD=coupon mysql -ucoupon -t coupon \
  -e "SELECT COUNT(*) FROM issuance;"

# Redis 재고
kubectl -n coupon exec statefulset/redis -- redis-cli KEYS 'coupon:*'
kubectl -n coupon exec statefulset/redis -- redis-cli GET 'coupon:1:stock'
kubectl -n coupon exec statefulset/redis -- redis-cli INFO clients

# 커넥션 수 (병목 확인)
kubectl -n coupon exec statefulset/mysql -- \
  mysql -uroot -proot -e "SHOW STATUS LIKE 'Threads_connected';"
```

## 스케일 / 배포

```bash
kubectl -n coupon scale deployment/coupon-service --replicas=5
kubectl -n coupon rollout restart deployment/coupon-service
kubectl -n coupon rollout status  deployment/coupon-service
kubectl -n coupon rollout history deployment/coupon-service
kubectl -n coupon rollout undo    deployment/coupon-service
kubectl -n coupon rollout pause   deployment/coupon-service   # 카나리 중단점
kubectl -n coupon rollout resume  deployment/coupon-service
```

## 리소스 사용량

```bash
kubectl top nodes
kubectl -n coupon top pods
kubectl -n coupon top pods --containers
kubectl -n coupon describe node coupon-worker | grep -A12 'Allocated resources'
```

## 매니페스트 검증

```bash
kubectl apply -f x.yaml --dry-run=server        # API 서버 검증 (권장)
kubectl kustomize k8s/overlays/local            # 렌더링 결과 보기
kubectl diff -k k8s/overlays/local              # 현재 클러스터와의 차이 ★
kubectl explain deployment.spec.strategy        # 필드 문서 (오프라인)
kubectl explain pod.spec.containers.livenessProbe --recursive
```

`kubectl explain` 은 인터넷 없이 스펙을 찾는 가장 빠른 방법이다.

## 노드 관리

```bash
kubectl get nodes -o wide
kubectl cordon   coupon-worker                  # 새 Pod 스케줄 금지
kubectl drain    coupon-worker --ignore-daemonsets --delete-emptydir-data
kubectl uncordon coupon-worker
```

## kind 전용

```bash
kind get clusters
kind load docker-image <image> --name coupon
docker exec coupon-control-plane crictl images   # 노드 안 이미지 목록
docker exec -it coupon-worker bash               # 노드에 셸로 들어가기
kind delete cluster --name coupon
```

## 자주 하는 실수 모음

1. **`kind load` 를 잊는다** → 옛날 이미지가 돈다. 코드 수정 반영 안 됨.
2. **`imagePullPolicy: IfNotPresent` 를 빼먹는다** → `:latest` 는 기본이 `Always` 라 pull 실패.
3. **`-n coupon` 을 빼먹는다** → `default` 네임스페이스를 보며 "아무것도 없다"고 헤맨다.
4. **ConfigMap 값에 따옴표를 안 쓴다** → `6379` 가 정수로 파싱돼 apply 거부.
5. **Service selector 오타** → Pod 은 Running 인데 연결 안 됨. `get endpoints` 로 확인.
6. **liveness 에 DB 를 넣는다** → DB 순단이 앱 전멸로 증폭.
7. **`preStop` 없이 롤링 업데이트** → 배포마다 502 가 튄다.
8. **HPA 와 `replicas` 를 같이 관리** → 서로 덮어쓰며 싸운다.
9. **PVC 를 안 지우고 재배포** → 옛 데이터가 살아 돌아온다 (때론 원하는 동작, 때론 버그).
10. **`describe` 의 Events 를 안 본다** → 답이 거기 있는데 로그만 뒤진다.

## 컨텍스트 관리

```bash
kubectl config get-contexts
kubectl config use-context kind-coupon
kubectl config set-context --current --namespace=coupon

# 셸 별칭 (~/.zshrc)
alias k=kubectl
alias kc='kubectl -n coupon'
source <(kubectl completion zsh)
complete -F __start_kubectl k
```

## 더 볼 것

- `kubectl explain` — 오프라인 스펙 조회
- [kubernetes.io/docs/concepts](https://kubernetes.io/docs/concepts/) — 공식 개념 문서
- `kubectl get <resource> -o yaml` — 실제 적용된 값 확인 (기본값이 어떻게 채워졌는지 보인다)
