# Ảnh và tệp trong đoạn chat

Chọn **Đính kèm ảnh** hoặc **Đính kèm tệp**, sau đó bấm nút gửi. Mỗi lần gửi nhận một tệp tối đa 10 MiB. PNG, JPEG, GIF và WebP hiện trong đoạn chat. Hai người tham gia đều tải xuống được. Hồ sơ đoạn chat liệt kê tệp trong phần lịch sử đã tải. Chức năng áp dụng cho các tài khoản VoIP trên web.

## Chạy MinIO bằng Docker

1. Đặt `MINIO_ROOT_USER=voip-local` và một `MINIO_ROOT_PASSWORD` ngẫu nhiên trong `.env` của backend.
2. Từ thư mục backend, chạy `docker compose up -d --build minio`. Docker build bản phát hành mã nguồn MinIO `RELEASE.2025-10-15T17-29-55Z`. Kho dữ liệu nằm trong volume `minio-data`. [Bản phát hành MinIO](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
3. Khởi động lại backend để đọc cấu hình. Mặc định S3 ở `http://127.0.0.1:29000` và console ở `http://127.0.0.1:29001`. Hai cổng chỉ bind localhost. Spring tạo bucket riêng tư `voip-attachments` khi upload đầu tiên nếu bucket chưa có.

Cấu hình `MINIO_ENDPOINT` khi backend chạy trên máy hoặc mạng khác. Có thể tạo bucket trước và dùng cặp `MINIO_ACCESS_KEY` và `MINIO_SECRET_KEY` dành riêng cho backend. Sao lưu volume MinIO cùng với PostgreSQL để khôi phục được cả nội dung và lịch sử tệp.

## Kiểm tra API

Request **Send Attachment** trong Postman cần `attachment_file` là đường dẫn tệp, một `attachment_request_id` mới và SIP peer của tài khoản nhận. Dùng trường `id` từ phản hồi để đặt `attachment_message_id` cho **Download Attachment**. Cùng peer, tên và nội dung có thể gửi lại với cùng request ID để phục hồi khi mất phản hồi.

Để chạy ca kiểm thử dùng Docker MinIO, đặt `TEST_MINIO=true` và `MINIO_BUCKET=voip-attachments-test` khi chạy `mvnw.cmd test`. Bucket test chứa ảnh và tệp mẫu do kiểm thử tạo.
