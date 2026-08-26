#!/usr/bin/env bash
# part-2 초과발급 시나리오 통합 러너.
#
# 사용:
#   ./scripts/load/part-2/run.sh
#
# reset -> create_coupon -> k6 -> verify 를 한 번에 실행한다.

set -euo pipefail
cd "$(dirname "$0")/../../.."

printf '\n\033[1;36m===== part-2 초과발급 시나리오 =====\033[0m\n'
./scripts/load/reset.sh

coupon_id="$(./scripts/load/create_coupon.sh)"
k6 run -e COUPON_ID="$coupon_id" scripts/load/part-2/over_issuance.js
COUPON_ID="$coupon_id" ./scripts/load/part-2/verify.sh