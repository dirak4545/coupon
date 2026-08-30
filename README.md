# coupon-service

## 실행

```bash
   # 이미지 빌드 (Docker daemon 필요)
docker compose up -d       # MySQL + 앱 기동
```

종료:

```bash
docker compose down -v     # 컨테이너 + 볼륨 제거
```

## API 호출

[scripts/api.sh](scripts/api.sh) (`jq` 필요)

## 쿠버네티스 (part-2-kub)

docker compose 환경을 쿠버네티스로 옮기고, **앱 replica 3개에서도 초과발급이 없는지** 재검증한다.

학습 자료: [docs/k8s/README.md](docs/k8s/README.md)
