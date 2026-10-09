# Đặc tả hệ thống giám sát, cảnh báo và sao lưu thiết bị LAN

**Ngày:** 2026-10-09  
**Trạng thái:** Thiết kế đã được người dùng duyệt  
**Nền tảng Agent:** Windows và Linux  
**Thời gian triển khai:** 5 tuần, nhóm 4 thành viên

## 1. Mục tiêu

Xây dựng hệ thống Client–Server trong mạng LAN. Mỗi máy cần quản lý chạy một Agent Java để thu thập tình trạng hệ thống, phát hiện dấu hiệu bất thường và cảnh báo người dùng lưu công việc. Agent theo dõi một thư mục được bảo vệ và tự động gửi bản sao của các file đã được ứng dụng lưu xuống đĩa lên Server. Quản trị viên có thể xem các phiên bản trên Dashboard và yêu cầu Server gửi một phiên bản về đúng Agent để khôi phục.

Hệ thống tập trung thể hiện TCP Socket, giao thức ứng dụng, nhiều kết nối đồng thời, heartbeat, truyền file theo khối, checksum, xử lý lỗi và trao đổi hai chiều.

## 2. Giới hạn và cách diễn đạt

- Hệ thống cảnh báo theo các dấu hiệu quan sát được; không tuyên bố dự đoán chắc chắn máy sắp sập nguồn.
- Agent không thể lưu nội dung chưa được ứng dụng ghi xuống file và không tự gửi tổ hợp phím `Ctrl+S`.
- Backup chỉ áp dụng với file đã được lưu trong thư mục được bảo vệ.
- Nhiệt độ chỉ được sử dụng khi hệ điều hành, cảm biến và quyền truy cập cung cấp giá trị thật; nếu không thì hiển thị `N/A`.
- Bản demo chỉ dùng dữ liệu thử nghiệm, không dùng dữ liệu cá nhân thật.

## 3. Phạm vi

### 3.1. Trong phạm vi

- Agent Java chạy trên Windows và Linux.
- Đăng ký thiết bị bằng `deviceId` và token riêng.
- Heartbeat và trạng thái Online/Offline.
- Thu thập CPU, RAM, dung lượng ổ đĩa, pin và nhiệt độ khi được hỗ trợ.
- Phát hiện nguy cơ theo ngưỡng và khoảng thời gian duy trì.
- Hiện cảnh báo cục bộ yêu cầu người dùng lưu công việc.
- Theo dõi một thư mục được bảo vệ và các thư mục con.
- Tự động upload file mới hoặc thay đổi lên Server.
- Kiểm tra SHA-256 và giữ tối đa 5 phiên bản mỗi file.
- Dashboard JavaFX quản lý thiết bị, sự cố và backup.
- Một tài khoản quản trị viên được phép yêu cầu khôi phục.
- Khôi phục file về thư mục `Recovered/` trên đúng Agent, không ghi đè file gốc.
- Lưu metadata, sự cố, yêu cầu khôi phục và nhật ký bằng SQLite.

### 3.2. Ngoài phạm vi

- Tự lưu dữ liệu chưa được ứng dụng ghi xuống file.
- Dự đoán chắc chắn mất điện hoặc sập nguồn.
- Điều khiển tắt máy, khởi động lại hoặc chạy lệnh hệ điều hành.
- Học máy, phân tích dự báo và biểu đồ lịch sử nâng cao.
- Tài khoản tự phục vụ cho người dùng Client.
- Giám sát qua Internet, lưu trữ phân tán và đồng bộ cloud.
- Mã hóa đầu cuối. TLS là hướng mở rộng; MVP chỉ chạy trong LAN với dữ liệu thử nghiệm.

## 4. Kiến trúc

```text
Agent Windows ─┐
Agent Linux  ──┼── Control TCP :5050 ── Server + Dashboard ── SQLite
Agent khác   ──┘           │                    │
                           └── File TCP :5051 ──┴── backup-storage/
```

Hệ thống dùng hai kênh TCP:

- Kênh điều khiển `5050`: kết nối dài hạn cho đăng ký, heartbeat, metrics, cảnh báo và lệnh khôi phục.
- Kênh file `5051`: kết nối ngắn hạn cho từng lần upload hoặc download để việc truyền file không chặn heartbeat.

### 4.1. Agent

- `ControlConnection`: duy trì kết nối điều khiển và tự kết nối lại.
- `SystemMetricsCollector`: dùng OSHI thu thập thông tin thật.
- `RiskDetector`: đánh giá các luật cảnh báo.
- `LocalAlertService`: hiển thị cảnh báo JavaFX; dùng thông báo hệ thống khi hỗ trợ.
- `ProtectedFolderWatcher`: theo dõi thư mục và thư mục con.
- `BackupQueue`: lưu công việc chờ trong SQLite cục bộ của Agent.
- `BackupUploader`: upload file và xử lý phản hồi.
- `RestoreReceiver`: nhận file khôi phục và lưu vào `Recovered/`.
- `AgentStateRepository`: lưu checksum đã backup, hàng chờ và cấu hình.

Agent là ứng dụng có giao diện nhỏ, không chạy dưới dạng Windows Service hoặc Linux daemon trong MVP.

### 4.2. Server

- `ControlTcpServer`: lắng nghe cổng điều khiển.
- `FileTransferServer`: lắng nghe cổng truyền file.
- `ClientSessionManager`: quản lý phiên bằng `ExecutorService`.
- `MessageDispatcher`: xác thực và phân phối thông điệp.
- `DeviceRegistry`: quản lý thiết bị, token, phiên và `lastSeen`.
- `HeartbeatMonitor`: xác định Offline.
- `AlertEngine`: lưu và cập nhật vòng đời sự cố.
- `BackupService`: xác nhận metadata, checksum, quota và phiên bản.
- `RestoreService`: tạo yêu cầu và token khôi phục dùng một lần.
- `Repository`: lưu metadata trong SQLite.
- `Dashboard`: giao diện quản trị JavaFX.

## 5. Phát hiện nguy cơ và cảnh báo

| Nguy cơ | Điều kiện mặc định |
|---|---|
| CPU cao | CPU ≥ 90% liên tục 30 giây |
| RAM cao | RAM ≥ 90% liên tục 15 giây |
| Ổ đĩa gần đầy | Dung lượng đã dùng ≥ 95% |
| Quá nhiệt | Nhiệt độ ≥ 85°C nếu cảm biến hỗ trợ |
| Pin sắp hết | Pin ≤ 10% và không sạc |
| Mất liên lạc Server | Agent không duy trì được kết nối điều khiển |

Khi có nguy cơ, Agent:

1. Hiện cảnh báo cục bộ với giá trị đo và lời nhắc lưu công việc.
2. Gửi `RISK_ALERT` lên Server nếu còn kết nối.
3. Không lặp lại popup liên tục cho cùng một nguy cơ đang mở.
4. Gửi `RISK_RESOLVED` khi điều kiện trở lại bình thường.

Nếu nhiệt độ không khả dụng thì Agent gửi `null`/`N/A` và không tạo cảnh báo nhiệt độ.

## 6. Theo dõi thư mục và backup

Mỗi Agent theo dõi một thư mục cấu hình, gồm các thư mục con. Ví dụ:

```text
Windows: C:\Users\Student\Documents\Protected
Linux:   /home/student/Documents/Protected
```

Khi file được tạo hoặc thay đổi:

1. Agent đợi file ổn định ít nhất 3 giây.
2. Bỏ qua file tạm như `~$*`, `*.tmp`, `*.swp` và symbolic link.
3. Từ chối file lớn hơn 100 MB trong MVP.
4. Tính SHA-256.
5. Nếu checksum khác phiên bản gần nhất, thêm file vào hàng chờ.
6. Mở kết nối cổng `5051`, gửi metadata rồi truyền file theo từng khối.
7. Server ghi vào file tạm, xác minh kích thước và SHA-256 rồi mới công bố phiên bản.
8. Agent chỉ đánh dấu thành công sau khi nhận `BACKUP_RESULT`.

Agent không chỉnh sửa hoặc xóa file nguồn. Nếu mất mạng, hàng chờ được giữ trong SQLite cục bộ và thử lại sau khi kết nối phục hồi. Khi Agent khởi động, nó quét lại thư mục để phát hiện thay đổi xảy ra trong thời gian Agent dừng.

Server giữ tối đa 5 phiên bản cho mỗi cặp `deviceId + relativePath`. Phiên bản cũ nhất chỉ bị xóa sau khi phiên bản mới đã được lưu và xác minh thành công. Xóa file gốc không tự động xóa bản backup.

## 7. Khôi phục do quản trị viên điều khiển

Chỉ tài khoản quản trị viên trên Dashboard được xem metadata backup và yêu cầu khôi phục.

1. Quản trị viên chọn thiết bị, file và phiên bản.
2. Server tạo `restore_job` cùng token một lần có thời hạn ngắn.
3. Nếu Agent Online, Server gửi `RESTORE_AVAILABLE`; nếu Offline, công việc giữ trạng thái `PENDING`.
4. Agent mở kết nối cổng `5051` và gửi token.
5. Server truyền file; Agent kiểm tra kích thước và SHA-256.
6. Agent lưu file tại `Protected/Recovered/<timestamp>/<relativePath>`.
7. Agent không ghi đè file hiện tại.
8. Agent gửi `RESTORE_RESULT`; Server cập nhật trạng thái và nhật ký.

## 8. Giao thức ứng dụng

### 8.1. Khung điều khiển

Mỗi thông điệp điều khiển gồm số nguyên 4 byte chỉ độ dài payload và JSON UTF-8 có đúng số byte đó. Payload tối đa 64 KiB.

| Loại | Chiều | Mục đích |
|---|---|---|
| `REGISTER` | Agent → Server | Đăng ký và xác thực thiết bị |
| `REGISTER_ACK` | Server → Agent | Kết quả đăng ký |
| `HEARTBEAT` | Agent → Server | Duy trì trạng thái Online |
| `METRICS` | Agent → Server | Gửi thông số hệ thống |
| `RISK_ALERT` | Agent → Server | Mở/cập nhật nguy cơ |
| `RISK_RESOLVED` | Agent → Server | Kết thúc nguy cơ |
| `RESTORE_AVAILABLE` | Server → Agent | Báo yêu cầu khôi phục |
| `RESTORE_RESULT` | Agent → Server | Kết quả khôi phục |
| `ERROR` | Hai chiều | Báo lỗi giao thức |

Agent gửi heartbeat và metrics mỗi 5 giây. Server đánh dấu Offline khi không nhận heartbeat trong 15 giây.

### 8.2. Phiên truyền file

Một phiên file gồm:

1. Metadata có độ dài xác định.
2. Phản hồi chấp nhận hoặc từ chối.
3. Đúng số byte nội dung đã khai báo, truyền theo khối.
4. Kết quả kiểm tra SHA-256.

Các thao tác chính: `BACKUP_UPLOAD`, `RESTORE_DOWNLOAD`, `BACKUP_RESULT` và `FILE_ERROR`.

## 9. Bảo vệ truy cập

- Mỗi Agent có `deviceId` và token riêng do Server cấp.
- Dashboard có một tài khoản quản trị viên; mật khẩu lưu dưới dạng hash có salt.
- Agent chỉ upload vào vùng của chính `deviceId`.
- Khôi phục cần token một lần, gắn với thiết bị và phiên bản, có thời hạn.
- Server chuẩn hóa `relativePath` và từ chối đường dẫn thoát khỏi vùng lưu trữ.
- Mọi backup và khôi phục đều được ghi nhật ký.
- MVP không được dùng trên mạng không tin cậy hoặc với dữ liệu thật khi chưa bổ sung TLS.

## 10. Mô hình dữ liệu

Server dùng các bảng:

- `admin_users`: tài khoản và mật khẩu hash.
- `devices`: thiết bị, token hash, nền tảng, trạng thái và `last_seen`.
- `latest_metrics`: thông số mới nhất.
- `incidents`: nguy cơ `OPEN`/`RESOLVED`.
- `backup_files`: một đường dẫn logic của một thiết bị.
- `backup_versions`: metadata từng phiên bản và đường dẫn lưu vật lý.
- `restore_jobs`: yêu cầu `PENDING`, `IN_PROGRESS`, `COMPLETED` hoặc `FAILED`.
- `audit_logs`: hành động đăng nhập, backup và khôi phục.

Nội dung file nằm trong `backup-storage/<deviceId>/`; SQLite không chứa blob nội dung file.

Agent dùng SQLite cục bộ cho:

- Cấu hình thư mục được bảo vệ.
- Checksum phiên bản đã upload gần nhất.
- Hàng chờ backup và số lần thử lại.

## 11. Xử lý lỗi

- Agent không đọc được cảm biến: dùng `N/A`, không giả số liệu.
- File còn đang được ghi hoặc bị khóa: trì hoãn và thử lại.
- File thay đổi trong lúc upload: không đánh dấu hoàn thành; xếp lại phiên bản mới.
- Mất mạng giữa upload: Server xóa file tạm; Agent giữ hàng chờ.
- Server hết dung lượng hoặc file vượt giới hạn: từ chối với lỗi rõ ràng.
- Checksum sai: không công bố phiên bản và yêu cầu upload lại.
- Agent trùng `deviceId` hoặc token sai: từ chối phiên mới.
- Restore đến Agent Offline: giữ `PENDING`.
- File đã tồn tại ở vị trí khôi phục: tạo tên không trùng, không ghi đè.
- Lỗi một Agent không làm dừng listener hoặc phiên Agent khác.

## 12. Kiểm thử và tiêu chí chấp nhận

| Mã | Tình huống | Kết quả mong đợi |
|---|---|---|
| TC01 | Agent Windows đăng ký | Online và metrics thật hiển thị |
| TC02 | Agent Linux đăng ký | Online và metrics thật hiển thị |
| TC03 | Nhiều Agent đồng thời | Các phiên hoạt động độc lập |
| TC04 | CPU/RAM vượt ngưỡng đủ lâu | Cảnh báo cục bộ và sự cố trên Server |
| TC05 | Không có cảm biến nhiệt | Hiển thị `N/A`, không cảnh báo giả |
| TC06 | File mới hoặc thay đổi | Tạo phiên bản backup hợp lệ |
| TC07 | File không thay đổi | Không upload phiên bản trùng checksum |
| TC08 | File tạm hoặc trên 100 MB | Bỏ qua/từ chối đúng quy tắc |
| TC09 | Mất mạng giữa upload | Không tạo phiên bản lỗi; Agent thử lại |
| TC10 | Checksum sai | Server từ chối file |
| TC11 | Tạo phiên bản thứ sáu | Chỉ giữ 5 phiên bản gần nhất |
| TC12 | Người chưa đăng nhập | Không thể yêu cầu khôi phục |
| TC13 | Agent Offline khi restore | Công việc giữ `PENDING` |
| TC14 | Agent Online trở lại | Restore tiếp tục và hoàn thành |
| TC15 | Khôi phục file | File nằm trong `Recovered/`, không ghi đè |
| TC16 | Đường dẫn `../` | Server từ chối truy cập ngoài vùng |
| TC17 | Một Agent lỗi | Agent khác vẫn hoạt động |

## 13. Phân công nhóm 4 thành viên

| Vai trò | Phụ trách |
|---|---|
| Thành viên 1 | TCP Server, hai kênh, phiên và giao thức |
| Thành viên 2 | Agent, OSHI, phát hiện nguy cơ và cảnh báo đa nền tảng |
| Thành viên 3 | Theo dõi thư mục, truyền file, checksum và phiên bản |
| Thành viên 4 | Dashboard, SQLite, đăng nhập và khôi phục |

Cả nhóm chịu trách nhiệm tích hợp, kiểm thử Windows/Linux, tài liệu và demo.

## 14. Kế hoạch 5 tuần

### Tuần 1: Nền tảng mạng

- Cấu trúc dự án Server/Agent.
- Codec, đăng ký, token, heartbeat và nhiều Agent.
- Xác nhận kết nối trên Windows và Linux.

### Tuần 2: Giám sát và cảnh báo

- OSHI, metrics, pin và nhiệt độ khi hỗ trợ.
- `RiskDetector` và cảnh báo cục bộ.
- Dashboard thiết bị và sự cố.

### Tuần 3: Backup

- Theo dõi thư mục, lọc file và đợi file ổn định.
- Hàng chờ, upload theo khối, SHA-256 và 5 phiên bản.

### Tuần 4: Khôi phục và tích hợp

- Đăng nhập quản trị, duyệt phiên bản và restore job.
- Download về Agent, lưu trong `Recovered/`.
- Xử lý Offline, retry và các lỗi tích hợp.

### Tuần 5: Ổn định

- Chạy TC01–TC17 trên Windows và Linux.
- Sửa lỗi, đóng gói, hướng dẫn chạy, báo cáo và video.
- Không bổ sung chức năng mới.

## 15. Tiêu chí hoàn thành

Hệ thống hoàn thành khi Agent Windows và Linux gửi metrics thật, cảnh báo người dùng khi có nguy cơ, tự động backup file đã lưu trong một thư mục được bảo vệ, Server giữ tối đa 5 phiên bản, chỉ quản trị viên được yêu cầu khôi phục và file được trả về đúng Agent trong `Recovered/` mà không ghi đè dữ liệu hiện tại. Toàn bộ TC01–TC17 phải đạt với dữ liệu thử nghiệm.
