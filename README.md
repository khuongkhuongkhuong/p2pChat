# P2P Chat

Ứng dụng chat P2P bằng Java, gồm web client, node P2P và bootstrap server để hỗ trợ đăng ký peer, khám phá mạng và truyền tin trực tiếp.

## Tổng quan

Dự án triển khai:

- `BootstrapServer` làm tracker/bootstrapping server
- `App` làm web server + frontend cho người dùng
- `P2PNode` làm peer node, gửi/nhận tin nhắn qua TCP
- `UserStore` quản lý đăng ký và đăng nhập
- `ChatHistoryStore` lưu lịch sử chat cục bộ

## Những cập nhật so với phiên bản cũ

- Bootstrap server giờ lưu tin nhắn chờ vào file `pending_messages.json` thay vì chỉ giữ trong bộ nhớ.
- Bootstrap server giữ trạng thái tin nhắn chờ qua restart, giúp cải thiện độ bền của store-and-forward.
- Gửi tin nhắn trực tiếp (`sendDirect`) giờ chờ ACK từ receiver và thử lại tối đa 2 lần trước khi báo lỗi.
- Receiver trả về `ACK` cho mỗi tin nhắn thành công.
- Mã hóa tin nhắn được nâng cấp từ `AES/ECB` sang `AES/CBC` với IV ngẫu nhiên.
- Xử lý gửi tin nhắn offline tin cậy hơn và giảm rủi ro gửi thất bại do kết nối tạm thời.

## Tính năng chính

- Peer discovery qua bootstrap TCP và UDP discovery
- Danh sách peer online do bootstrap cung cấp
- Gửi tin nhắn riêng P2P, chat nhóm và broadcast
- Lưu tin nhắn chờ khi peer offline
- Lưu lịch sử chat cục bộ cho từng cuộc trò chuyện
- Mã hóa nội dung tin nhắn giữa peer
- Gửi file trực tiếp giữa peer qua TCP

## Kiến trúc

- `BootstrapServer`:
  - lắng nghe TCP trên port `8888`
  - UDP discovery trên port `8889`
  - đăng ký peer, trả về danh sách peer online
  - lưu và chuyển tiếp tin nhắn chờ
  - duy trì timeout để loại peer mất kết nối

- `App`:
  - dùng Javalin phục vụ frontend và API
  - xử lý đăng ký, xác thực, login/logout
  - gọi `P2PNode` để bắt đầu listener và gửi tin
  - cung cấp endpoint cho frontend lấy peer, lịch sử, gửi tin và gửi file

- `P2PNode`:
  - phát hiện IP cục bộ và mở cổng ngẫu nhiên
  - lắng nghe tin nhắn TCP song song
  - gửi tin nhắn riêng, nhóm, broadcast
  - gửi tin nhắn trực tiếp với cơ chế ACK
  - mã hóa/giải mã nội dung tin nhắn

## Yêu cầu

- Java 17
- Maven 3.x

## Cài đặt

1. Mở terminal tại thư mục dự án:
   ```powershell
   cd ...\p2pChat-main
   ```
2. Biên dịch và đóng gói:
   ```powershell
   mvn package
   ```

## Chạy ứng dụng

### 1) Khởi động Bootstrap Server

```powershell
java -jar target/p2p-bootstrap.jar
```

### 2) Khởi động Web App

```powershell
java -jar target/p2p-app.jar
```

Hoặc chỉ định bootstrap host:

```powershell
java -jar target/p2p-app.jar 192.168.x.x
```

Hoặc dùng biến môi trường:

```powershell
$env:BOOTSTRAP_HOST = '192.168.x.x'
java -jar target/p2p-app.jar
```

### Truy cập giao diện

Mở trình duyệt và truy cập URL hiển thị trong console, ví dụ:

```text
http://localhost:7000
```

> Ứng dụng sẽ tìm cổng web trống bắt đầu từ `7000`.

## Sử dụng

1. Đăng ký tài khoản mới.
2. Đăng nhập và vào mạng P2P.
3. Xem danh sách peer online.
4. Chọn peer để chat riêng, chọn nhóm để chat nhóm.
5. Gửi file tới peer khác qua tính năng upload.
6. Xem lại lịch sử chat trong `chat_history/`.

## Cấu trúc thư mục

- `pom.xml` - cấu hình Maven
- `src/main/java` - mã nguồn Java
- `src/main/resources/public` - frontend tĩnh `index.html`, `style.css`
- `users.json` - lưu tài khoản người dùng
- `pending_messages.json` - lưu tin nhắn chờ của bootstrap
- `chat_history/` - lưu lịch sử chat

## Ghi chú

- `users.json` được tạo khi người dùng đăng ký.
- `pending_messages.json` lưu tin nhắn chờ để bootstrap không mất dữ liệu khi restart.
- Bootstrap chỉ cho phép peer đăng ký nếu tài khoản đã tồn tại.
- File nhận được được lưu tạm trong bộ nhớ RAM và có thể tải xuống qua API.

## Hướng mở rộng

Bạn có thể mở rộng dự án bằng cách:

- làm lại frontend với React/Vue
- thêm cơ chế đồng bộ trạng thái nhóm thực sự
- sử dụng TLS/SSL cho kết nối TCP
- gửi file lớn theo chunk và resume
- thêm multi-hop relay hoặc discovery không phụ thuộc bootstrap
