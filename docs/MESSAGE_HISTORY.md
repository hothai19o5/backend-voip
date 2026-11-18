# Tin nhắn web và lịch sử SIP MESSAGE

PostgreSQL lưu tin của từng tài khoản trong `message_history`. Tin giữa hai tài
khoản web được lưu cho cả hai bên trong cùng giao dịch. Người nhận có thể đăng
nhập sau để tải tin. Trạng thái accepted xác nhận backend đã lưu tin. API chưa
có xác nhận đã đọc. Bearer token xác định tài khoản và dữ liệu vẫn còn sau
khi đăng xuất. Nội dung lưu dạng văn bản chưa mã hóa.

Contract (được đồng bộ thủ công với `openapi.yaml` và collection Postman):

- `PUT /api/v1/message-history/send/{id}` nhận `peer` (SIP URI) và `body`
  (tối đa 5.000 ký tự). Backend tra máy nhánh đã gán trong DB, xác định người gửi
  từ bearer token, đặt thời gian và trả bản tin outgoing sau khi lưu cả hai phía.
  Dùng cùng ID và nội dung khi thử lại. Khi cùng ID có nội dung khác, API trả
  409 `MESSAGE_CONFLICT`. Nếu máy nhánh thuộc tài khoản không dùng được, API trả
  `CHAT_UNAVAILABLE`. Chỉ 404 `SIP_ONLY` cho phép frontend gửi qua SIP MESSAGE.
- `PUT /api/v1/message-history/messages/{id}`: lưu lịch sử SIP theo tài khoản + id
  local (1–100 ký tự). Body: `account`, `peer` (SIP URI, tối đa 320 ký tự),
  `name` (tên hội thoại, tối đa 255), `direction` (`incoming`/`outgoing`), `body`
  (1–65535 ký tự), `status` (`pending`/`accepted`/`failed`/`received`), `time`
  (epoch milliseconds > 0), `senderName` (tùy chọn, tối đa 255). Trả 200 bản ghi
  đã lưu. Chỉ cho đổi pending sang accepted/failed; retry không đổi nội dung,
  không tạo bản ghi trùng và không hạ trạng thái cuối về pending. Dùng lại id với
  nội dung khác trả 409 `MESSAGE_CONFLICT`. Tiền tố `chat:` dành riêng cho tin web.
- `GET /api/v1/message-history/conversations?before=&size=30`: mỗi phần tử gồm
  `peer`, `name`, `lastMessage`. Sắp theo thứ tự ghi nhận mới nhất, cursor là
  sequence của phần tử cuối; không tải toàn bộ tin vào danh sách hội thoại.
- `GET /api/v1/message-history/messages?peer=sip:1002@pbx.test&before=&size=50`:
  tin của hội thoại từ mới đến cũ. Cursor dùng sequence tăng dần do DB cấp,
  không dùng timestamp nên không bỏ sót khi nhiều tin cùng thời gian.
- `GET /api/v1/message-history/updates?after=0&size=100` trả các bản ghi mới của
  tài khoản theo sequence tăng dần. Khi đăng nhập, frontend lấy sequence mới
  nhất từ danh sách hội thoại, hoặc 0 nếu trống. Mỗi lượt tiếp tục sau sequence
  cuối đã nhận. `nextCursor` báo còn trang tiếp theo.

Ba GET trả `{ content: [...], nextCursor: string | null }`, size 1–100.
Mỗi message có các trường PUT và `id`, `sequence` (chuỗi số). Cursor phải là số
nguyên 64 bit, cho phép 0 ở tham số `after`. Peer chuẩn hóa giống frontend: bỏ sip/sips, tham số/query,
domain lowercase. Nội dung giữ nguyên, kể cả xuống dòng/Unicode/HTML dạng text.
API không phụ thuộc FusionPBX khi đọc/ghi lịch sử. Lỗi validation trả 400;
request thiếu hoặc hết phiên dùng xử lý 401 hiện có.

Frontend tự tải hội thoại, tải 50 tin mới nhất khi mở thread, nút tải thêm cho
tin/hội thoại cũ. App đang hiển thị đồng bộ tin mới mỗi 3 giây và thử đồng bộ
ngay khi quay lại app hoặc kết nối mạng. Gộp theo id, giữ bản nháp và trạng thái
realtime. Tin pending khôi phục hiển thị chưa rõ kết quả gửi. Lưu lỗi giữ hàng đợi
trong RAM, báo chưa lưu và cho thử lại (tự thử lại khi mạng online). Hàng đợi và
bản nháp mất khi đóng/reload/logout; các tin đã lưu vẫn tồn tại.

## Kiểm chứng

2026-11-18: 45 tests backend qua trên H2 (8 tests chat/lịch sử, 34 Auth/Users/SIP
và 3 TLS). Các ca mới kiểm tra nhận khi offline, cursor, giới hạn truy cập
và gửi đồng thời hai chiều với yêu cầu trùng ID.

`scripts/run-history-verification.ps1` chạy JAR thật + PostgreSQL với handler
FusionPBX giả lập local (cùng source Postman). Năm request trong collection qua
7 assertions. Sau khi restart JAR và tắt giả lập FusionPBX, người nhận đăng nhập
lần đầu và lấy được đúng một bản tin Unicode. Script yêu cầu JAR đã package và database test tồn tại,
tự dọn các process của nó trên port 14011/18083. Có tham số JavaHome/DatabaseUrl/
DatabaseUsername/DatabasePassword; mặc định khớp database test trên compose dev.
Không publish dữ liệu Postman. Không gọi hoặc thay đổi PBX thật.

Frontend: `frontend-voip/scripts/run-frontend-verification.ps1 -HistoryOnly`
chạy 7 nhóm QA Chrome với API/SIP giả lập, gồm đăng nhập lại, phân trang, lỗi
đọc/lưu, gộp tin mới với snapshot cũ và nhận tin sau khi đăng xuất hoặc mất mạng.
