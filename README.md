# P2P Chat Application

## Mục lục

1. [Giới thiệu dự án](#1-giới-thiệu-dự-án)
2. [Kiến trúc hệ thống](#2-kiến-trúc-hệ-thống)
3. [Yêu cầu môi trường](#3-yêu-cầu-môi-trường)
4. [Hướng dẫn cài đặt](#4-hướng-dẫn-cài-đặt)
5. [Hướng dẫn chạy project](#5-hướng-dẫn-chạy-project)
6. [Hướng dẫn chạy hệ thống P2P](#6-hướng-dẫn-chạy-hệ-thống-p2p)
7. [Cấu trúc project](#7-cấu-trúc-project)
8. [Tính năng](#8-tính-năng)
9. [API Endpoints](#9-api-endpoints)
10. [Hướng dẫn test](#10-hướng-dẫn-test)
11. [Các lỗi thường gặp](#11-các-lỗi-thường-gặp)

---

## 1. Giới thiệu dự án

**P2P Chat Application** là hệ thống nhắn tin ngang hàng (Peer-to-Peer) viết bằng Java. Thay vì toàn bộ tin nhắn đi qua một máy chủ trung tâm, các peer giao tiếp **trực tiếp với nhau** sau khi đã được Bootstrap Server giới thiệu địa chỉ.

### Mục tiêu hệ thống

- Cho phép nhiều người dùng chat với nhau qua mạng LAN hoặc Internet mà không cần backend truyền thống.
- Hỗ trợ nhắn tin 1-1, chat nhóm, gửi file nhị phân.
- Tin nhắn được mã hóa AES-128 CBC trước khi truyền.
- Khi người nhận offline, tin nhắn được lưu tạm (store-and-forward) tại Bootstrap Server và giao lại khi người đó đăng nhập.

### Công nghệ sử dụng

| Thành phần      | Công nghệ                               |
| --------------- | --------------------------------------- |
| Ngôn ngữ        | Java 17                                 |
| Build tool      | Maven 3.x                               |
| Web framework   | Javalin 6.1.3                           |
| JSON            | Jackson Databind 2.16.1                 |
| Mã hóa mật khẩu | BCrypt (at.favre.lib 0.10.2)            |
| Mã hóa tin nhắn | AES-128/CBC/PKCS5 (Java built-in)       |
| Logging         | SLF4J Simple 2.0.9                      |
| Lưu trữ dữ liệu | File JSON (không dùng database)         |
| Giao diện       | HTML + CSS + JavaScript thuần           |
| Giao thức mạng  | TCP (chat, file) + UDP (auto-discovery) |

---

## 2. Kiến trúc hệ thống

```
┌─────────────────────────────────────────────────────────────────┐
│                        MẠNG LAN / INTERNET                      │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              BOOTSTRAP SERVER (máy chạy riêng)           │   │
│  │                                                          │   │
│  │   TCP :8888  ←── REGISTER / STORE / LEAVE               │   │
│  │   UDP :8889  ←── DISCOVER_BOOTSTRAP (auto-discovery)    │   │
│  │                                                          │   │
│  │   Lưu: users.json  groups.json  pending_messages.json   │   │
│  └──────────────────────────────────────────────────────────┘   │
│          ↑ heartbeat (3s)          ↑ heartbeat (3s)             │
│          │                         │                            │
│  ┌───────┴──────┐         ┌────────┴────────┐                  │
│  │  PEER A      │         │   PEER B        │                  │
│  │  Web :7000   │◄───────►│   Web :7000     │                  │
│  │  P2P :random │  TCP    │   P2P :random   │                  │
│  │  Browser UI  │ direct  │   Browser UI    │                  │
│  └──────────────┘         └─────────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
```

### Các module chính

| Module / Package   | Vai trò                                                                        |
| ------------------ | ------------------------------------------------------------------------------ |
| `bootstrap`        | Bootstrap Server – trung tâm đăng ký, lưu pending messages, auto-discovery UDP |
| `node`             | P2PNode – lắng nghe kết nối đến, gửi tin trực tiếp, mã hóa AES, gửi file       |
| `web`              | App – khởi động HTTP server (Javalin), expose REST API cho giao diện web       |
| `auth`             | UserStore, GroupStore, ChatHistoryStore – quản lý users, nhóm, lịch sử chat    |
| `resources/public` | Giao diện web (HTML + CSS + JS thuần, không framework)                         |

### Flow hoạt động cơ bản

```
1. Khởi động Bootstrap Server
      └─ Lắng nghe TCP :8888 + UDP :8889

2. Mỗi Peer khởi động (java -jar p2p-app.jar [bootstrap-host])
      ├─ Auto-discover Bootstrap qua UDP broadcast (nếu không truyền arg)
      ├─ Khởi động Javalin HTTP server tại port tự động (từ 7000)
      └─ Giao diện web mở tại http://localhost:<webPort>

3. Người dùng đăng nhập trên trình duyệt
      ├─ P2PNode lắng nghe TCP tại port ngẫu nhiên
      └─ Heartbeat gửi REGISTER| lên Bootstrap mỗi 3 giây

4. Gửi tin nhắn đến Peer khác
      ├─ [ONLINE]  → kết nối TCP trực tiếp, mã hóa AES, gửi
      └─ [OFFLINE] → gửi STORE| lên Bootstrap, giao lại khi peer online

5. Chat nhóm
      └─ Gửi đồng thời đến tất cả member đang online trong nhóm
```

---

## 3. Yêu cầu môi trường

| Yêu cầu      | Phiên bản tối thiểu     | Ghi chú                                          |
| ------------ | ----------------------- | ------------------------------------------------ |
| Java JDK     | 17+                     | Bắt buộc; dự án compile với `--release 17`       |
| Apache Maven | 3.8+                    | Dùng để build, không cần cài nếu chỉ chạy `.jar` |
| Hệ điều hành | Windows / macOS / Linux | Đa nền tảng                                      |
| Trình duyệt  | Chrome / Firefox / Edge | Để truy cập giao diện web                        |
| Kết nối mạng | LAN hoặc Internet       | Các peer phải có thể reach nhau                  |

> **Không cần cài database.** Dữ liệu được lưu dưới dạng file `users.json`, `groups.json`, `pending_messages.json` trong thư mục chạy.

---

## 4. Hướng dẫn cài đặt

### 4.1 Clone project

```bash
git clone <repository-url>
cd "p2p-app/Source code"
```

Hoặc giải nén từ file zip:

```bash
unzip p2p-app.zip
cd "p2p-app/Source code"
```

### 4.2 Kiểm tra Java

```bash
java -version
# Cần: java version "17.x.x" trở lên

mvn -version
# Cần: Apache Maven 3.x
```

Nếu chưa có Java 17, tải tại: https://adoptium.net/

### 4.3 Build project

```bash
cd "Source code"
mvn clean package -DskipTests
```

Sau khi build thành công, thư mục `target/` sẽ có:

```
target/
├── p2p-app.jar        ← Fat JAR cho Peer (bao gồm web UI)
└── p2p-bootstrap.jar  ← Fat JAR riêng cho Bootstrap Server
```

### 4.4 Chuẩn bị file dữ liệu

Hệ thống tự tạo các file JSON khi cần. Nếu muốn khởi động với dữ liệu sẵn (tài khoản test), copy file từ thư mục gốc:

```bash
# Chạy từ thư mục "Source code/"
# users.json và groups.json đã có sẵn trong project
```

> **Lưu ý:** Cả Bootstrap Server và Peer đều đọc `users.json` từ **thư mục hiện tại** lúc chạy, không phải từ bên trong JAR. Hãy chạy lệnh `java -jar` từ đúng thư mục chứa file JSON.

### 4.5 Cấu hình Bootstrap Host

**Mặc định: không cần cấu hình gì.** Peer tự động tìm Bootstrap Server trong LAN qua UDP broadcast. Chỉ cần truyền IP thủ công khi auto-discovery không hoạt động (khác subnet, firewall chặn UDP broadcast).

Thứ tự ưu tiên:

| Thứ tự | Cách                              | Ví dụ                                               |
| ------ | --------------------------------- | --------------------------------------------------- |
| 1      | Argument dòng lệnh                | `java -jar p2p-app.jar 192.168.1.10`                |
| 2      | Biến môi trường                   | `BOOTSTRAP_HOST=192.168.1.10 java -jar p2p-app.jar` |
| 3      | **UDP Auto-discovery** (mặc định) | Tự broadcast tìm trong LAN                          |
| 4      | Fallback                          | `localhost` nếu không tìm thấy                      |

---

## 5. Hướng dẫn chạy project

### 5.1 Chạy trên một máy (demo đơn)

**Bước 1:** Mở terminal thứ nhất – chạy Bootstrap Server

```bash
cd "Source code"
java -jar target/p2p-bootstrap.jar
```

Output mong đợi:

```
>>> Bootstrap Server started on port 8888...
>>> UDP Discovery listening on port 8889...
```

**Bước 2:** Mở terminal thứ hai – chạy Peer đầu tiên

```bash
cd "Source code"
java -jar target/p2p-app.jar
# Tự động tìm Bootstrap qua UDP, fallback về localhost
```

Output mong đợi:

```
>>> IP của máy này: 192.168.x.x
===========================================
>>> Giao diện chat: http://localhost:7000
===========================================
```

**Bước 3:** Mở trình duyệt tại `http://localhost:7000`

**Bước 4:** Mở terminal thứ ba – chạy Peer thứ hai

```bash
cd "Source code"
java -jar target/p2p-app.jar
# Tự tìm Bootstrap, web port tự động lên 7001
```

**Bước 5:** Mở tab trình duyệt mới tại `http://localhost:7001`

### 5.2 Tài khoản test có sẵn

Các tài khoản sau đã được tạo sẵn trong `users.json` (mật khẩu cần biết từ người tạo — hãy dùng tính năng **Đăng ký** để tạo tài khoản mới):

| Username | Ghi chú        |
| -------- | -------------- |
| khuong   | Tài khoản test |
| trang    | Tài khoản test |
| viet     | Tài khoản test |
| tiendz   | Tài khoản test |

> Vì mật khẩu được hash BCrypt và không lưu plaintext, hãy **đăng ký tài khoản mới** để test.

---

## 6. Hướng dẫn chạy hệ thống P2P

### 6.1 Sơ đồ nhiều peer trên nhiều máy

```
Máy A (IP: 192.168.1.10)          Máy B (IP: 192.168.1.20)
─────────────────────────          ─────────────────────────
Bootstrap:  TCP :8888              Peer 1:  Web :7000
            UDP :8889              Peer 2:  Web :7001 (nếu cần)
Peer local: Web :7000
```

### 6.2 Chạy Bootstrap Server (chỉ cần 1 máy)

```bash
# Trên máy bootstrap (ví dụ: 192.168.1.10)
cd "Source code"
java -jar target/p2p-bootstrap.jar
```

> Bootstrap Server mở 2 port:
>
> - **TCP 8888** – nhận REGISTER / STORE / LEAVE
> - **UDP 8889** – auto-discovery broadcast

### 6.3 Chạy Peer (bất kỳ máy nào trong LAN)

```bash
# Cách thông thường – tự tìm bootstrap qua UDP broadcast, không cần nhớ IP
java -jar target/p2p-app.jar

# Chỉ khi UDP bị chặn (khác subnet/VLAN, firewall) mới cần chỉ định thủ công
java -jar target/p2p-app.jar 192.168.1.10
```

Truy cập giao diện: `http://localhost:7000`

### 6.4 Chạy nhiều Peer trên cùng một máy

Web port tự động tăng, không cần cấu hình gì thêm:

```bash
# Terminal 2 → http://localhost:7000
java -jar target/p2p-app.jar

# Terminal 3 → http://localhost:7001
java -jar target/p2p-app.jar

# Terminal 4 → http://localhost:7002
java -jar target/p2p-app.jar
```

### 6.6 Ví dụ chạy 3 peer trên 3 máy

| Máy    | IP           | Lệnh khởi động                | Web UI                |
| ------ | ------------ | ----------------------------- | --------------------- |
| Server | 192.168.1.10 | `java -jar p2p-bootstrap.jar` | —                     |
| Peer 1 | 192.168.1.10 | `java -jar p2p-app.jar`       | http://localhost:7000 |
| Peer 2 | 192.168.1.20 | `java -jar p2p-app.jar`       | http://localhost:7000 |
| Peer 3 | 192.168.1.30 | `java -jar p2p-app.jar`       | http://localhost:7000 |

> Nếu UDP broadcast bị chặn (khác subnet), thêm IP bootstrap: `java -jar p2p-app.jar 192.168.1.10`

### 6.7 Thứ tự khởi động

```
1. Khởi động Bootstrap Server TRƯỚC
2. Đợi thông báo "Bootstrap Server started on port 8888"
3. Khởi động các Peer
4. Mỗi peer tự đăng ký với Bootstrap mỗi 3 giây (heartbeat)
```

### 6.8 Kiểm tra kết nối giữa các peer

Sau khi đăng nhập, danh sách peer online sẽ hiển thị trên giao diện. Trạng thái `ONLINE` / `OFFLINE` được cập nhật tự động dựa trên heartbeat (timeout 4 giây).

---

## 7. Cấu trúc project

```
Source code/
├── pom.xml                          ← Maven build config, dependencies
├── users.json                       ← Lưu tài khoản (tự tạo khi chạy)
├── groups.json                      ← Lưu nhóm chat (tự tạo khi chạy)
├── pending_messages.json            ← Tin nhắn chờ khi peer offline
├── chat_history/                    ← Lịch sử chat (file .txt theo peer)
│
└── src/main/java/com/p2pchat/
    ├── bootstrap/
    │   └── BootstrapServer.java     ← Server đăng ký peer, lưu pending msg
    │
    ├── node/
    │   └── P2PNode.java             ← Core P2P: lắng nghe, gửi, mã hóa AES
    │
    ├── web/
    │   └── App.java                 ← HTTP server (Javalin), REST API, heartbeat
    │
    └── auth/
        ├── UserStore.java           ← Đăng ký, đăng nhập, BCrypt hash
        ├── GroupStore.java          ← Tạo, xóa, quản lý nhóm
        └── ChatHistoryStore.java    ← Lưu/đọc lịch sử chat

src/main/resources/public/
    ├── index.html                   ← Giao diện web (SPA, HTML + JS thuần)
    └── style.css                    ← Styling giao diện
```

---

## 8. Tính năng

### Xác thực người dùng

- **Đăng ký** tài khoản: username chỉ dùng chữ/số/gạch dưới, mật khẩu tối thiểu 6 ký tự.
- **Đăng nhập / Đăng xuất**: mật khẩu được hash BCrypt (cost 12), không lưu plaintext.

### Chat 1-1 (Peer-to-Peer trực tiếp)

- Gửi tin nhắn trực tiếp đến peer đang online qua TCP socket.
- Tin nhắn được **mã hóa AES-128/CBC/PKCS5** trước khi gửi.
- Nếu peer offline: tin nhắn được lưu tạm tại Bootstrap, giao lại khi peer đăng nhập.

### Chat nhóm

- Tạo nhóm với danh sách thành viên tùy chọn.
- Gửi tin nhắn đến tất cả member online trong nhóm cùng lúc.
- Chủ nhóm có thể xóa nhóm.
- Thành viên được thông báo khi được mời vào nhóm hoặc nhóm bị xóa.

### Broadcast

- Gửi tin nhắn đến **tất cả peer đang online** cùng lúc.

### Gửi file

- Gửi file nhị phân trực tiếp đến peer khác qua TCP.
- File được lưu tạm trong RAM của peer nhận, có thể tải xuống qua trình duyệt.

### Quản lý trạng thái

- Danh sách peer online/offline được cập nhật tự động mỗi 3 giây.
- Peer bị timeout sau 4 giây không heartbeat.

### Lịch sử chat

- Lịch sử tin nhắn 1-1 và nhóm được lưu vào file text trong thư mục `chat_history/`.
- Có thể xem lại lịch sử trò chuyện qua giao diện web.

### Auto-discovery

- Peer tự tìm Bootstrap Server qua UDP broadcast trong LAN (không cần cấu hình thủ công).

---

## 9. API Endpoints

Web server chạy tại `http://localhost:<webPort>` (mặc định 7000).

### Xác thực

| Method | URL             | Tham số                | Mô tả                         |
| ------ | --------------- | ---------------------- | ----------------------------- |
| POST   | `/api/register` | `username`, `password` | Đăng ký tài khoản mới         |
| POST   | `/api/auth`     | `username`, `password` | Xác thực thông tin đăng nhập  |
| POST   | `/api/login`    | `name`, `password`     | Đăng nhập và khởi tạo P2PNode |
| POST   | `/api/logout`   | —                      | Đăng xuất, thông báo rời mạng |

### Thông tin peer

| Method | URL                   | Tham số | Mô tả                                       |
| ------ | --------------------- | ------- | ------------------------------------------- |
| GET    | `/api/info`           | —       | Trả về username, port, IP của peer hiện tại |
| GET    | `/api/peers`          | —       | Danh sách peer online/offline               |
| GET    | `/api/bootstrap-host` | —       | IP của bootstrap server đang dùng           |
| GET    | `/api/discover`       | —       | Tìm bootstrap qua UDP                       |

### Nhắn tin

| Method | URL              | Tham số                       | Mô tả                                     |
| ------ | ---------------- | ----------------------------- | ----------------------------------------- |
| POST   | `/api/send`      | `target`, `targetName`, `msg` | Gửi tin 1-1 hoặc broadcast                |
| POST   | `/api/sendgroup` | `groupId`, `msg`              | Gửi tin nhắn vào nhóm                     |
| GET    | `/api/messages`  | —                             | Lấy tin nhắn mới nhận (và xóa khỏi queue) |

### Nhóm

| Method | URL                  | Tham số                           | Mô tả                            |
| ------ | -------------------- | --------------------------------- | -------------------------------- |
| POST   | `/api/creategroup`   | `groupId`, `groupName`, `members` | Tạo nhóm mới                     |
| POST   | `/api/deletegroup`   | `groupId`                         | Xóa nhóm (chỉ chủ nhóm)          |
| GET    | `/api/groups`        | —                                 | Danh sách nhóm của user hiện tại |
| GET    | `/api/group-members` | `groupId`                         | Danh sách thành viên của nhóm    |

### Lịch sử & File

| Method | URL                  | Tham số                      | Mô tả                         |
| ------ | -------------------- | ---------------------------- | ----------------------------- |
| GET    | `/api/conversations` | —                            | Danh sách các cuộc trò chuyện |
| GET    | `/api/history`       | `key`                        | Lịch sử chat theo key         |
| POST   | `/api/sendfile`      | `target`, `file` (multipart) | Gửi file đến peer             |
| GET    | `/api/download`      | `fileId`                     | Tải file đã nhận              |

---

## 10. Hướng dẫn test

### Test cơ bản (1 máy, 2 peer)

```bash
# Terminal 1: Bootstrap
java -jar target/p2p-bootstrap.jar

# Terminal 2: Peer A
java -jar target/p2p-app.jar localhost

# Terminal 3: Peer B
java -jar target/p2p-app.jar localhost
```

1. Mở `http://localhost:7000` → Đăng ký tài khoản `alice` → Đăng nhập.
2. Mở `http://localhost:7001` → Đăng ký tài khoản `bob` → Đăng nhập.
3. Trên giao diện của `alice`, chọn `bob` trong danh sách peer → Gửi tin nhắn.
4. Kiểm tra `bob` nhận được tin.

### Test store-and-forward (tin nhắn khi offline)

1. Đăng nhập với `alice` và `bob`.
2. Tắt peer của `bob` (Ctrl+C terminal Peer B).
3. Từ giao diện `alice`, gửi tin nhắn cho `bob` (trạng thái OFFLINE).
4. Khởi động lại peer `bob`, đăng nhập lại.
5. Kiểm tra `bob` nhận được tin nhắn bị trễ.

### Test chat nhóm

1. Đăng nhập 3 tài khoản trên 3 tab trình duyệt.
2. Từ tài khoản thứ nhất, tạo nhóm và thêm 2 tài khoản còn lại.
3. Gửi tin nhắn vào nhóm.
4. Kiểm tra 2 tài khoản kia đều nhận được.

### Test gửi file

1. Đăng nhập hai tài khoản.
2. Chọn peer đích → dùng chức năng gửi file → chọn file bất kỳ.
3. Bên nhận kiểm tra nút tải file xuất hiện.

### Test broadcast

1. Đăng nhập nhiều tài khoản.
2. Gửi tin broadcast từ một tài khoản.
3. Tất cả peer online đều phải nhận được.

---

## 11. Các lỗi thường gặp

### Port bị trùng

**Triệu chứng:**

```
Address already in use: bind
```

**Cách fix:**

- Port web (7000+) tự động tìm port trống, không cần can thiệp.
- Port Bootstrap (8888, 8889) cố định. Kiểm tra và kill process:

```bash
# Windows
netstat -ano | findstr :8888
taskkill /PID <PID> /F

# Linux / macOS
lsof -i :8888
kill -9 <PID>
```

---

### Peer không kết nối được với Bootstrap

**Triệu chứng:** Danh sách peer luôn trống, không thấy user khác.

**Nguyên nhân thường gặp:**

- Bootstrap chưa khởi động.
- Sai IP bootstrap (khi chạy qua mạng).
- Firewall block port 8888/8889.

**Cách fix:**

```bash
# Kiểm tra bootstrap đang chạy
telnet <bootstrap-ip> 8888

# Truyền IP bootstrap rõ ràng
java -jar target/p2p-app.jar 192.168.1.10

# Tắt firewall tạm (Linux)
sudo ufw allow 8888/tcp
sudo ufw allow 8889/udp

# Tắt firewall tạm (Windows)
netsh advfirewall set allprofiles state off
```

---

### Peer kết nối bootstrap nhưng không gửi được tin cho nhau

**Nguyên nhân:** Peer-to-peer TCP bị firewall chặn, hoặc địa chỉ IP nhận diện sai.

**Cách fix:**

- Đảm bảo port TCP ngẫu nhiên (ephemeral) không bị firewall chặn.
- Kiểm tra IP hiển thị tại `/api/info` — phải là IP LAN thật, không phải 127.0.0.1.
- Nếu 2 máy không cùng LAN (qua Internet), cần port forwarding hoặc VPN.

---

### Maven build lỗi

**Triệu chứng:**

```
[ERROR] COMPILATION ERROR
```

**Cách fix:**

```bash
# Kiểm tra phiên bản Java
java -version  # Phải là 17+

# Xóa cache và build lại
mvn clean package -DskipTests

# Nếu lỗi dependency, xóa cache Maven
rm -rf ~/.m2/repository/com/p2pchat
mvn clean package -DskipTests
```

---

### File users.json / groups.json không tìm thấy

**Triệu chứng:**

```
Không đọc được pending messages
```

**Cách fix:**

- Phải chạy lệnh `java -jar` từ thư mục `Source code/`, không phải từ thư mục `target/`.

```bash
# Đúng
cd "Source code"
java -jar target/p2p-app.jar

# Sai
cd "Source code/target"
java -jar p2p-app.jar   ← users.json sẽ không được tìm thấy
```

---

### Lịch sử chat không lưu

**Nguyên nhân:** Thư mục `chat_history/` chưa tồn tại.

**Cách fix:** Thư mục tự tạo khi có tin nhắn đầu tiên. Nếu không, tạo thủ công:

```bash
mkdir -p "Source code/chat_history"
```

---
