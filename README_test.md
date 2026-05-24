# P2P Chat - Change Log

File này ghi lại các cải thiện và thay đổi đã thực hiện cho dự án sau khi kiểm tra và cập nhật mã.

## Những cải tiến chính

- Cập nhật `BootstrapServer` để lưu tin nhắn chờ vào file `pending_messages.json`.
  - Trước đó tin nhắn chờ chỉ tồn tại trong bộ nhớ, gây mất dữ liệu khi bootstrap server restart.
  - Giờ bootstrap giữ trạng thái và phục hồi tin chờ giữa các lần khởi động.

- Nâng cấp cơ chế gửi tin nhắn riêng (P2P) trong `P2PNode`:
  - Thêm chờ xác nhận `ACK` từ phía receiver.
  - Thử lại gửi tối đa 2 lần nếu peer không phản hồi.
  - Nếu vẫn thất bại, dựa vào cơ chế store-and-forward để gửi tin qua bootstrap.

- Cập nhật receiver để trả về `ACK` sau khi nhận và xử lý thành công tin nhắn.

- Cải thiện mã hóa nội dung tin nhắn:
  - Chuyển từ `AES/ECB/PKCS5Padding` sang `AES/CBC/PKCS5Padding`.
  - Thêm IV ngẫu nhiên cho mỗi tin nhắn để tăng tính an toàn.

## Mục tiêu của các thay đổi

- Tăng độ tin cậy truyền tin giữa các peer.
- Giảm mất tin khi bootstrap restart.
- Cải thiện cơ chế store-and-forward và xử lý offline.
- Nâng cao tính bảo mật của nội dung tin nhắn P2P.

## Ghi chú

- `README.md` vẫn giữ vai trò giới thiệu tổng quan dự án.
- `README_test.md` chỉ dùng để ghi lại các thay đổi và cải tiến đã thực hiện.
