# 07. Kustomize 로 환경 분리

## 왜

지금까지 만든 파일들에는 환경마다 달라야 할 값이 하드코딩돼 있다.

- `replicas: 3` — 로컬은 1개면 충분하고 운영은 10개일 수도
- `type: NodePort` — 운영은 LoadBalancer/Ingress
- `requests.memory: 768Mi` — 로컬 노트북에서는 부담
- 이미지 태그 `latest` — 운영은 반드시 고정 태그

파일을 복사해서 환경마다 두면 **똑같은 수정을 여러 번 해야 하고 반드시 어긋난다.**

Kustomize 는 **base 를 그대로 두고 patch 만 얹는다.** kubectl 에 내장돼 있어서 설치도 필요 없다.

> Helm 과의 차이: Helm 은 템플릿 엔진(`{{ .Values.x }}`)이라 표현력이 크지만 원본 YAML 이 아니게 된다.
> Kustomize 는 **유효한 YAML 을 유효한 YAML 로 변환**한다. 배우기 쉽고 diff 가 읽힌다.
> 남이 만든 복잡한 차트를 쓸 땐 Helm, 내 매니페스트를 관리할 땐 Kustomize 가 무난하다.

## 1) 디렉터리 구조

```
k8s/
├── base/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── mysql.yaml
│   ├── redis.yaml
│   ├── app.yaml
│   ├── pdb.yaml
│   └── kustomization.yaml
└── overlays/
    ├── local/
    │   ├── kustomization.yaml
    │   └── replicas-patch.yaml
    └── prod/
        ├── kustomization.yaml
        ├── resources-patch.yaml
        └── hpa.yaml
```

## 2) base

`k8s/base/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: coupon

resources:
  - namespace.yaml
  - configmap.yaml
  - secret.yaml
  - mysql.yaml
  - redis.yaml
  - app.yaml
  - pdb.yaml
  # hpa.yaml 은 일부러 뺐다. HPA 가 있으면 06 의 replica 실험이 계속 덮어써진다.
  # 자동 스케일이 필요한 prod overlay 에서만 추가한다.

# 모든 오브젝트에 공통 라벨을 붙인다.
# includeSelectors: false 가 중요하다. true 면 Deployment 의 selector 까지 바뀌는데,
# selector 는 불변(immutable) 필드라 기존 Deployment 에 apply 하면 실패한다.
labels:
  - pairs:
      app.kubernetes.io/part-of: coupon-service
      app.kubernetes.io/managed-by: kustomize
    includeSelectors: false

images:
  - name: coupon-service
    newTag: latest
```

렌더링 결과를 먼저 눈으로 본다 (**apply 전에 항상 이걸 하는 습관을 들이자**).

```bash
kubectl kustomize k8s/base | less
```

```bash
kubectl apply -k k8s/base
```

## 3) local overlay

`k8s/overlays/local/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: coupon

resources:
  - ../../base

# 간단한 값 변경은 전용 필드로 끝난다.
replicas:
  - name: coupon-service
    count: 1

patches:
  - path: replicas-patch.yaml
```

`k8s/overlays/local/replicas-patch.yaml` — 노트북에 맞게 리소스를 줄인다.

```yaml
# strategic merge patch: 바꾸고 싶은 필드만 적으면 나머지는 base 가 유지된다.
apiVersion: apps/v1
kind: Deployment
metadata:
  name: coupon-service
spec:
  template:
    spec:
      containers:
        - name: app          # ← 이름으로 매칭한다. 반드시 base 와 같아야 한다.
          resources:
            requests:
              cpu: "200m"
              memory: "512Mi"
            limits:
              memory: "768Mi"
```

```bash
kubectl kustomize k8s/overlays/local | grep -A6 'resources:'
kubectl apply -k k8s/overlays/local
```

## 4) prod overlay

`k8s/overlays/prod/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: coupon

resources:
  - ../../base
  - hpa.yaml               # prod 에만 HPA 를 추가

# 운영은 절대 latest 를 쓰지 않는다. 롤백이 불가능해지기 때문.
images:
  - name: coupon-service
    newName: registry.example.com/coupon-service
    newTag: "0.0.1-SNAPSHOT"

patches:
  - path: resources-patch.yaml
  # JSON 6902 patch: 배열 요소나 필드 삭제처럼 정밀한 조작에 쓴다.
  - target:
      kind: Service
      name: coupon-service
    patch: |-
      - op: replace
        path: /spec/type
        value: ClusterIP
      - op: remove
        path: /spec/ports/0/nodePort

  # ConfigMap 값 덮어쓰기
  - target:
      kind: ConfigMap
      name: coupon-config
    patch: |-
      - op: replace
        path: /data/SPRING_PROFILES_ACTIVE
        value: prod
      - op: replace
        path: /data/JAVA_TOOL_OPTIONS
        value: "-XX:MaxRAMPercentage=70 -XX:+UseZGC"
```

> **주의**: `configMapGenerator` 의 `behavior: merge` 는 **base 도 generator 로 만들어진 ConfigMap** 일 때만 쓸 수 있다.
> 우리는 base 에서 `configmap.yaml` 을 `resources` 로 선언했으므로
> (`does not exist; cannot merge or replace` 오류가 난다) patch 로 덮어써야 한다.
> generator 를 쓰고 싶다면 base 의 `resources` 에서 `configmap.yaml` 을 빼고
> base 의 `kustomization.yaml` 에서 `configMapGenerator` 로 만들어야 한다.

`k8s/overlays/prod/resources-patch.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: coupon-service
spec:
  template:
    spec:
      containers:
        - name: app
          resources:
            requests:
              cpu: "1000m"
              memory: "2Gi"
            limits:
              memory: "2Gi"
      # 운영은 서로 다른 노드에 강제로 흩뿌린다.
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule     # local 의 ScheduleAnyway 보다 엄격
          labelSelector:
            matchLabels:
              app: coupon-service
```

`k8s/overlays/prod/hpa.yaml` 은 05 에서 만든 파일을 그대로 복사해 온다.

```bash
cp k8s/base/hpa.yaml k8s/overlays/prod/hpa.yaml
```

```bash
kubectl kustomize k8s/overlays/prod          # apply 하지 말고 결과만 확인
diff <(kubectl kustomize k8s/base) <(kubectl kustomize k8s/overlays/prod) | head -60
```

## 5) 두 패치 방식의 차이

| | Strategic Merge Patch | JSON 6902 Patch |
|---|---|---|
| 문법 | 원본과 같은 모양의 YAML | `op/path/value` 목록 |
| 잘하는 것 | 필드 추가·수정 | 필드 **삭제**, 배열 인덱스 조작 |
| 배열 처리 | `name` 등 merge key 로 병합 | 인덱스로 지정 |
| 가독성 | 좋다 | 떨어진다 |

**필드를 지워야 하면 JSON patch 를 쓴다.** 위에서 `nodePort` 를 지운 게 그 예다.
Strategic merge 로는 필드 삭제가 안 된다 (`null` 트릭이 있지만 동작이 헷갈린다).

## 6) Secret 은 어떻게 하나

02 에서 말했듯 Secret 을 git 에 커밋하면 안 된다. 현실적인 선택지:

1. **`secretGenerator` + `.env` 파일** (`.gitignore` 에 추가) — 가장 간단

   base 의 `resources` 에서 `secret.yaml` 을 **빼고**, 대신 이렇게 만든다.
   (같은 이름의 Secret 을 resource 로도 두고 generator 로도 만들면 중복 오류가 난다)

   ```yaml
   # k8s/base/kustomization.yaml
   secretGenerator:
     - name: coupon-secret
       envs:
         - secret.env        # git 에 올리지 않는다
   ```

   `k8s/base/secret.env` (`.gitignore` 에 추가):
   ```
   MYSQL_ROOT_PASSWORD=root
   MYSQL_DATABASE=coupon
   MYSQL_USER=coupon
   MYSQL_PASSWORD=coupon
   SPRING_DATASOURCE_PASSWORD=coupon
   ```
   생성된 Secret 이름 뒤에 해시 접미사가 붙어(`coupon-secret-7f8k2m9`),
   **값이 바뀌면 이름이 바뀌고 → Deployment 가 자동으로 롤링 업데이트된다.** 이게 큰 장점이다.
   (`generatorOptions.disableNameSuffixHash: true` 로 끌 수도 있지만, 켜두는 편이 낫다)

2. **SOPS / sealed-secrets** — 암호화된 채로 커밋. GitOps 와 궁합이 좋다.
3. **External Secrets Operator** — AWS Secrets Manager, Vault 등에서 런타임에 주입. 운영 표준에 가깝다.

이 실습에서는 1번이면 충분하다.

## 7) 최종 확인 흐름

```bash
# 1. 렌더링 결과를 먼저 본다
kubectl kustomize k8s/overlays/local

# 2. 서버에 무엇이 바뀌는지 dry-run 으로 확인
kubectl apply -k k8s/overlays/local --dry-run=server

# 3. 적용
kubectl apply -k k8s/overlays/local

# 4. 결과 확인
kubectl -n coupon get all
```

`--dry-run=server` 는 실제 API 서버가 검증까지 해주므로 `--dry-run=client` 보다 훨씬 유용하다.

## 8) 전체 정리

```bash
kubectl delete -k k8s/overlays/local
# 또는 통째로
kubectl delete namespace coupon      # PVC 포함 전부 삭제된다
kind delete cluster --name coupon
```

## 체크포인트

1. `labels` 에서 `includeSelectors: false` 를 빼면 기존 Deployment 에 apply 할 때 왜 실패하나?
2. Service 의 `nodePort` 를 지우려면 왜 strategic merge patch 로는 안 되나?
3. `secretGenerator` 의 이름 해시 접미사가 실용적으로 어떤 이점을 주나?
4. 운영에서 이미지 태그로 `latest` 를 쓰면 안 되는 이유는?

<details>
<summary>답</summary>

1. `spec.selector.matchLabels` 는 **불변(immutable) 필드**다. 라벨이 selector 에까지 추가되면
   기존 Deployment 의 selector 를 바꾸는 셈이라 API 서버가 거부한다.
2. strategic merge 는 "합치기"라서 필드를 없앨 수단이 없다. JSON 6902 의 `op: remove` 가 필요하다.
3. Secret 값이 바뀌면 이름이 바뀌고, 그 이름을 참조하는 Deployment 의 스펙도 바뀌어
   **롤링 업데이트가 자동으로 트리거된다.** 환경변수 주입은 재시작해야만 반영되므로 이게 중요하다.
4. 어떤 커밋이 배포됐는지 알 수 없고, 롤백할 대상을 특정할 수 없다.
   같은 태그라도 Pod 마다 다른 이미지가 뜰 수 있어 재현도 안 된다.
</details>

---
[99-cheatsheet.md](99-cheatsheet.md) 로 마무리
