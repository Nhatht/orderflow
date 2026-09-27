#!/usr/bin/env bash
# Kiểm tra end-to-end trên hệ thống ĐANG CHẠY (docker compose up): đặt đơn thật qua gateway
# và chờ saga ra đúng kết quả. CI chạy script này sau `docker compose up --wait`.
#
#   scripts/smoke-test.sh                      # gateway http://localhost:8080
#   BASE_URL=http://localhost:3000 scripts/smoke-test.sh   # đi qua nginx của frontend
#
# Cần: bash, curl, jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT_S="${TIMEOUT_S:-60}"

fail() { echo "FAIL: $*" >&2; exit 1; }

token=$(curl -sf -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice123"}' | jq -r .accessToken) || fail "login"
[ -n "$token" ] && [ "$token" != null ] || fail "login returned no token"
auth=(-H "Authorization: Bearer $token")
echo "login ok"

# Tham số: productId, productName, unitPrice, trạng thái saga mong đợi
place_and_wait() {
  local product_id=$1 product_name=$2 unit_price=$3 expected=$4
  local body order_id status deadline
  body=$(jq -n --arg id "$product_id" --arg name "$product_name" --argjson price "$unit_price" \
    '{currency:"VND", items:[{productId:$id, productName:$name, quantity:1, unitPrice:$price}]}')
  order_id=$(curl -sf -X POST "$BASE_URL/api/orders" "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$body" | jq -r .id) || fail "place order ($product_name)"

  deadline=$((SECONDS + TIMEOUT_S))
  while :; do
    # Saga được tạo ngay sau đơn; 404 trong khoảnh khắc đầu thì thử lại.
    status=$(curl -sf "$BASE_URL/api/orders/$order_id/saga" "${auth[@]}" | jq -r .status || echo PENDING)
    case "$status" in
      COMPLETED | COMPENSATED | FAILED) break ;;
    esac
    [ $SECONDS -lt $deadline ] || fail "order $order_id ($product_name): saga still $status after ${TIMEOUT_S}s"
    sleep 1
  done

  [ "$status" = "$expected" ] || fail "order $order_id ($product_name): saga $status, expected $expected"
  echo "order $order_id ($product_name): saga $status as expected"
}

# Kẹo dừa 45.000đ: thẻ được chấp nhận → giữ hàng, thu tiền, xác nhận.
place_and_wait 11111111-1111-1111-1111-111111111111 'Kẹo dừa Bến Tre' 45000 COMPLETED
# Trà atiso 30.099đ: tổng tận cùng 99 → cổng giả lập từ chối thẻ → nhả hàng (đền bù).
place_and_wait 44444444-4444-4444-4444-444444444444 'Trà atiso Đà Lạt' 30099 COMPENSATED

echo "smoke test passed"
