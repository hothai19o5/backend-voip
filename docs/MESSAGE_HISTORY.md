# Lịch sử SIP MESSAGE

Lịch sử là bản sao các tin nhắn mà từng tài khoản web đã gửi/nhận qua SIP,
lưu lâu dài trong PostgreSQL (`message_history`). Gửi/nhận vẫn dùng SIP MESSAGE;
API này không chuyển tiếp tin, không cung cấp gửi offline hay xác nhận đã đọc.
Không mã hóa nội dung lịch sử trong giai đoạn này. Dùng phiên bearer sẵn có để
chọn tài khoản, không nhận owner từ client. Đăng xuất không xóa dữ liệu đã lưu.

Contract (được đồng bộ thủ công với `openapi.yaml` và collection Postman):

- `PUT /api/v1/message-history/messages/{id}`: lưu idempotent theo tài khoản + id
  local (1–100 ký tự). Body: `account`, `peer` (SIP URI, tối đa 320 ký tự),
  `name` (tên hội thoại, tối đa 255), `direction` (`incoming`/`outgoing`), `body`
  (1–65535 ký tự), `status` (`pending`/`accepted`/`failed`/`received`), `time`
  (epoch milliseconds > 0), `senderName` (tùy chọn, tối đa 255). Trả 200 bản ghi
  đã lưu. Chỉ cho đổi pending sang accepted/failed; retry không đổi nội dung,
  không tạo bản ghi trùng và không hạ trạng thái cuối về pending. Dùng lại id với
  nội dung khác trả 409 `MESSAGE_CONFLICT`.
- `GET /api/v1/message-history/conversations?before=&size=30`: mỗi phần tử gồm
  `peer`, `name`, `lastMessage`. Sắp theo thứ tự ghi nhận mới nhất, cursor là
  sequence của phần tử cuối; không tải toàn bộ tin vào danh sách hội thoại.
- `GET /api/v1/message-history/messages?peer=sip:1002@pbx.test&before=&size=50`:
  tin của hội thoại từ mới đến cũ. Cursor dùng sequence tăng dần do DB cấp,
  không dùng timestamp nên không bỏ sót khi nhiều tin cùng thời gian.

Hai GET trả `{ content: [...], nextCursor: string | null }`; size 1–100.
Mỗi message có các trường PUT và `id`, `sequence` (chuỗi số). Cursor phải là số
nguyên dương 64 bit. Peer chuẩn hóa giống frontend: bỏ sip/sips, tham số/query,
domain lowercase. Nội dung giữ nguyên, kể cả xuống dòng/Unicode/HTML dạng text.
API không phụ thuộc FusionPBX khi đọc/ghi lịch sử. Lỗi validation trả 400;
request thiếu hoặc hết phiên dùng xử lý 401 hiện có.

Frontend tự tải hội thoại, tải 50 tin mới nhất khi mở thread, nút tải thêm cho
tin/hội thoại cũ. Gộp theo id với sự kiện đang đến, giữ bản nháp và trạng thái
realtime. Tin pending khôi phục hiển thị chưa rõ kết quả gửi. Lưu lỗi giữ hàng đợi
trong RAM, báo chưa lưu và cho thử lại (tự thử lại khi mạng online). Hàng đợi và
bản nháp mất khi đóng/reload/logout; các tin đã lưu vẫn tồn tại.

## Kiểm chứng

2026-11-18: 42 tests backend qua trên H2 (5 tests lịch sử, 34 Auth/Users/SIP,
3 TLS); 5 tests lịch sử cũng qua trên PostgreSQL thật trong database riêng
`voip_history_test`. Không đọc/ghi dữ liệu ứng dụng dev để kiểm thử lịch sử.

`scripts/run-history-verification.ps1` chạy JAR thật + PostgreSQL với handler
FusionPBX giả lập local (cùng source Postman). Ba request lịch sử trong collection
qua 4 assertions bằng Postman CLI; sau đó restart JAR, login lại và xác minh
nội dung Unicode vẫn còn. Script yêu cầu JAR đã package và database test tồn tại,
tự dọn các process của nó trên port 14011/18083. Có tham số JavaHome/DatabaseUrl/
DatabaseUsername/DatabasePassword; mặc định khớp database test trên compose dev.
Không publish dữ liệu Postman. Không gọi hoặc thay đổi PBX thật.

Frontend: `frontend-voip/scripts/run-frontend-verification.ps1 -HistoryOnly`
chạy 6 nhóm QA Chrome với API/SIP giả lập, gồm hai chiều, đăng nhập/tải lại,
phân trang, lỗi đọc/lưu và gộp tin mới với snapshot cũ. Toàn bộ runner còn kiểm
tra 16 nhóm ứng dụng và 6 nhóm cửa sổ gọi. 39 unit tests, build và lint qua
(lint có 3 warnings cũ). Kiểm thử browser dùng backend giả; kiểm thử Postman và
PostgreSQL ở trên dùng backend thật. Chưa xác minh luồng này trên PBX deployed.
