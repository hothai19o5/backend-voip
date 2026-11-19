# Triển khai Cuộc gọi

Khi quay số, React gọi `POST /api/v1/calls/resolve` bằng Bearer token. Spring Boot chuẩn hóa số và tra DB, rồi trả `sip:<máy-nhánh>@<domain>` cho số thuộc người dùng hoặc `sip:+84…@<domain>` cho số di động hoặc cố định Việt Nam chưa gán. SIP.js gửi INVITE thẳng tới URI nhận được qua WSS. Khi chọn người từ danh bạ, React gọi SIP máy nhánh như trước. Xem [bản thiết kế](../../docs/app-to-phone-design.md) và [OpenAPI](openapi.yaml).

## Bổ sung số cho tài khoản hiện có

1. Triển khai backend với `PHONE_CALLS_ENABLED=false`. Khi khởi động, Hibernate thêm cột `users.phone_e164` nullable và ràng buộc duy nhất. Đăng ký mới bắt buộc có `phoneNumber` ngay từ phiên bản này.
2. Trong thời gian bảo trì, dừng các instance ghi vào database. Chuẩn bị một file UTF-8 ngoài repo, mỗi dòng gồm username, một ký tự tab và số của tài khoản. File gồm toàn bộ tài khoản đang thiếu số, không có tiêu đề. Số được chuẩn hóa bằng cùng bộ kiểm tra với đăng ký.
3. Từ `backend-voip`, xác nhận cấu hình DB rồi chạy lượt kiểm tra. Sau khi xử lý hết số thiếu, trùng hoặc sai, chạy lại kèm `--app.phone-calls.backfill-apply=true` để ghi trong một giao dịch.

```powershell
java -jar target/backend-voip-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none '--app.phone-calls.backfill-file=D:\Private\account-phones.tsv'
```

4. Kiểm tra `SELECT COUNT(*) FROM users WHERE phone_e164 IS NULL;` trả `0`. Áp dụng ràng buộc trên DB dev sau khi backfill.

```sql
ALTER TABLE users ALTER COLUMN phone_e164 SET NOT NULL;
```

Tài khoản cũ giữ ID và máy nhánh. Số tự khai áp dụng cho bản dev. Số của chính tài khoản được trả trong hồ sơ đăng nhập và `/users/me`. Danh bạ giữ các trường công khai hiện có.

## Kết nối FreeSWITCH

1. Tạo SIP gateway cho OpenVox SWG-M204 trong FusionPBX. Dùng [mẫu outbound route](../deploy/freeswitch/voip-vn-outbound.xml) để định tuyến số `+84…` tại context của máy nhánh app. Thay `REPLACE_WITH_GATEWAY_ID` bằng gateway ID thực tế. Mẫu gửi định dạng `+84…`. Dùng `0$1` ở action `bridge` nếu OpenVox nhận số dạng nội địa. [FusionPBX Outbound Routes](https://docs.fusionpbx.com/en/latest/dialplan/outbound_routes.html)
2. Giới hạn outbound route ở máy nhánh được phép và xác thực SIP INVITE. Mẫu yêu cầu Digest (`auth-calls=true`) trong SIP profile của app và kiểm tra các nhóm số di động hoặc cố định Việt Nam. Các giới hạn số cuộc đồng thời, cước và Caller ID do PBX hoặc gateway quản lý. Web tra DB trước mỗi lần gọi. SIP client khác có quyền dùng outbound route sẽ đi theo quy tắc của PBX.
3. Nếu đã nạp mẫu `phone-` cũ, gỡ mẫu đó và URL CDR dành riêng cho ứng dụng khỏi FreeSWITCH. Sử dụng outbound route SIP ở bước 1 cho MVP. Khi chuyển sang Carrier SIP Trunk, đổi gateway, định dạng số và Caller ID trong FusionPBX.
4. Nạp lại dialplan, kiểm tra trạng thái gateway, đặt `PHONE_CALLS_ENABLED=true` và khởi động lại backend. Đăng nhập lại hoặc tải lại trang để thấy **Cuộc gọi**. Backend chỉ bật quay số khi mọi tài khoản đã có số.

## Lịch sử và kiểm thử

Trình duyệt lưu kết quả SIP cùng số đã quay qua `POST /api/v1/calls`. Cuộc gọi theo máy nhánh xuất hiện trong đoạn chat và **Gần đây**. Cuộc PSTN do web thực hiện xuất hiện trong **Gần đây**. Các ca đóng tab trước khi trình duyệt báo kết quả cần đối chiếu CDR trên PBX.

- Dùng hai số tài khoản đã biết để thử định tuyến app-to-app, kể cả lúc máy đích offline hoặc bận. Kiểm tra số leg qua OpenVox bằng 0 trong các ca này.
- Dùng số Việt Nam chưa gán do người kiểm thử kiểm soát để thử đổ chuông, thoại hai chiều, hủy và kết thúc. Kiểm tra Caller ID thực tế theo SIM hoặc carrier.
- Sau khi kết thúc, xác nhận **Gần đây** hiển thị một bản ghi và tuyến PSTN nằm ngoài danh sách đoạn chat. Bấm **Gọi lại** để xác minh backend tra lại mapping.
- Dùng **Resolve Phone Call** và **List Dialed Calls** trong collection [Auth and Users](../postman/collections/auth-users/) với Bearer token của người gọi.
