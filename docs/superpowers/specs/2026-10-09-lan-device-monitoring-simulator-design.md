# Đặc tả thiết kế hệ thống giám sát thiết bị LAN bằng Client Simulator

> **Đã được thay thế:** Đây là đặc tả lịch sử của phương án Client Simulator. Thiết kế hiện hành nằm tại [2026-10-09-agent-monitoring-backup-design.md](./2026-10-09-agent-monitoring-backup-design.md) và sử dụng Agent Windows/Linux, cảnh báo nguy cơ, backup phiên bản và khôi phục do quản trị viên điều khiển.

**Ngày:** 2026-10-09  
**Trạng thái:** Thiết kế đã được người dùng duyệt  
**Phạm vi:** Giai đoạn đầu của đồ án môn Lập trình mạng máy tính

## 1. Mục tiêu

Xây dựng nguyên mẫu hệ thống quản lý, giám sát và cảnh báo sự cố thiết bị trong mạng LAN theo mô hình Client–Server. Laptop của nhóm đóng vai trò Server trung tâm. Nhiều chương trình Client Simulator kết nối đến Server qua TCP, đăng ký thiết bị, gửi heartbeat và gửi số liệu CPU, RAM, ổ đĩa được mô phỏng.

Giai đoạn này tập trung chứng minh kiến thức lập trình mạng: TCP Socket, giao thức ứng dụng, xử lý nhiều kết nối đồng thời, đóng khung thông điệp, heartbeat, timeout và xử lý lỗi. Hệ thống chưa thu thập số liệu thật từ máy Client và chưa thực hiện điều khiển từ xa.

## 2. Phạm vi

### 2.1. Trong phạm vi

- Server TCP phục vụ đồng thời nhiều Client Simulator.
- Client đăng ký bằng mã thiết bị duy nhất.
- Heartbeat định kỳ và phát hiện trạng thái Online/Offline.
- Sinh và gửi dữ liệu CPU, RAM, ổ đĩa mô phỏng.
- Dashboard hiển thị trạng thái và số liệu mới nhất.
- Phát hiện sự cố bằng các ngưỡng cấu hình sẵn.
- Lưu thông tin thiết bị, số liệu mới nhất và lịch sử sự cố trong SQLite.
- Xử lý Client mất kết nối, thông điệp không hợp lệ và mã thiết bị trùng.

### 2.2. Ngoài phạm vi

- Agent chạy nền và thu thập số liệu thật bằng OSHI.
- Nhiệt độ CPU/GPU.
- Điều khiển tắt máy, khởi động lại hoặc chạy lệnh hệ điều hành.
- Học máy hoặc phát hiện bất thường nâng cao.
- Giám sát qua Internet, phân quyền nhiều cấp và mã hóa đầu cuối.
- Gửi email, SMS hoặc thông báo đẩy.
- Lưu toàn bộ mẫu metrics theo thời gian để vẽ biểu đồ lịch sử.

Các nội dung ngoài phạm vi chỉ được bổ sung sau khi phiên bản cốt lõi đã ổn định.

## 3. Kiến trúc tổng thể

```text
Client Simulator 1 ─┐
Client Simulator 2 ─┼── TCP Socket ── Server ── SQLite
Client Simulator N ─┘                    │
                                     Dashboard
```

### 3.1. Client Simulator

Mỗi Client Simulator đại diện cho một thiết bị và có các thành phần:

- `TcpConnection`: thiết lập, duy trì và đóng kết nối TCP.
- `DeviceRegistration`: gửi thông tin đăng ký sau khi kết nối.
- `HeartbeatScheduler`: gửi heartbeat mỗi 5 giây.
- `FakeMetricsGenerator`: sinh CPU, RAM và ổ đĩa trong khoảng 0–100%.
- `MessageCodec`: đóng gói và giải mã thông điệp.

Client cho phép nhập mã thiết bị, tên thiết bị, IP/port Server và chọn chế độ dữ liệu bình thường hoặc cố ý vượt ngưỡng. Giai đoạn này không yêu cầu Client tự động kết nối lại; chức năng đó có thể được bổ sung nếu còn thời gian.

### 3.2. Server

Server gồm các thành phần:

- `TcpServer`: lắng nghe kết nối trên một cổng cấu hình.
- `ClientSessionManager`: tạo một tác vụ trong `ExecutorService` cho từng kết nối.
- `MessageDispatcher`: kiểm tra loại thông điệp và chuyển đến bộ xử lý tương ứng.
- `DeviceRegistry`: quản lý thiết bị, phiên kết nối và thời điểm `lastSeen`.
- `HeartbeatMonitor`: định kỳ phát hiện thiết bị quá hạn heartbeat.
- `AlertEngine`: so sánh metrics với ngưỡng và quản lý vòng đời sự cố.
- `Repository`: đọc/ghi SQLite.
- `Dashboard`: hiển thị tổng quan, thiết bị và cảnh báo.

Lỗi của một phiên Client phải được cô lập, không làm dừng Server hoặc ảnh hưởng các Client còn lại.

### 3.3. Mở rộng thành Agent thật

Kiến trúc tách `FakeMetricsGenerator` khỏi tầng giao tiếp mạng. Ở giai đoạn sau, bộ sinh dữ liệu được thay bằng `SystemMetricsCollector` sử dụng OSHI. Giao thức, Server và Dashboard không cần thay đổi.

## 4. Giao thức ứng dụng

Mỗi thông điệp gồm:

1. Một số nguyên 4 byte biểu diễn độ dài payload.
2. Payload JSON mã hóa UTF-8 có đúng số byte đã khai báo.

Giới hạn kích thước thông điệp phải được cấu hình để Server từ chối payload bất thường. Giá trị đề xuất cho giai đoạn đầu là 64 KiB.

### 4.1. Loại thông điệp

| Loại | Chiều | Mục đích |
|---|---|---|
| `REGISTER` | Client → Server | Đăng ký thiết bị |
| `REGISTER_ACK` | Server → Client | Chấp nhận hoặc từ chối đăng ký |
| `HEARTBEAT` | Client → Server | Cập nhật thời điểm Client còn hoạt động |
| `METRICS` | Client → Server | Gửi CPU, RAM và ổ đĩa mô phỏng |
| `ERROR` | Hai chiều | Báo thông điệp hoặc trạng thái không hợp lệ |

### 4.2. Quy tắc phiên kết nối

- Client phải gửi `REGISTER` đầu tiên.
- Chỉ Client đã đăng ký thành công mới được gửi `HEARTBEAT` và `METRICS`.
- `deviceId` phải khác rỗng và duy nhất trong các phiên đang kết nối.
- CPU, RAM và ổ đĩa phải là số trong khoảng 0–100.
- Client gửi heartbeat và metrics mỗi 5 giây.
- Server đánh dấu Offline nếu không nhận heartbeat trong 15 giây.
- Server từ chối thông điệp vượt kích thước hoặc sai cấu trúc.
- Giai đoạn này Server không gửi lệnh quản trị xuống Client.

### 4.3. Ví dụ thông điệp

```json
{
  "type": "REGISTER",
  "deviceId": "PC-01",
  "deviceName": "May phong A01",
  "ipAddress": "192.168.1.21",
  "os": "Windows 11"
}
```

```json
{
  "type": "METRICS",
  "deviceId": "PC-01",
  "timestamp": 1791536400,
  "cpuPercent": 72.5,
  "ramPercent": 68.1,
  "diskPercent": 91.0
}
```

## 5. Luồng xử lý

### 5.1. Luồng bình thường

1. Client mở kết nối TCP đến IP/port Server.
2. Client gửi `REGISTER`.
3. Server kiểm tra dữ liệu và tính duy nhất của `deviceId`.
4. Server trả `REGISTER_ACK` thành công và đánh dấu thiết bị Online.
5. Client gửi `HEARTBEAT` và `METRICS` định kỳ.
6. Server cập nhật `lastSeen`, số liệu mới nhất và Dashboard.
7. `AlertEngine` kiểm tra ngưỡng và mở hoặc kết thúc sự cố.

### 5.2. Luồng mất kết nối

1. Server phát hiện socket đóng, lỗi đọc hoặc quá hạn heartbeat.
2. Server đóng tài nguyên phiên kết nối.
3. `DeviceRegistry` đánh dấu thiết bị Offline.
4. Dashboard cập nhật trạng thái nhưng giữ lại thông tin thiết bị.

## 6. Dashboard

Dashboard JavaFX chạy cùng tiến trình Server và có ba khu vực:

### 6.1. Tổng quan

- Tổng số thiết bị.
- Số thiết bị Online.
- Số thiết bị Offline.
- Số sự cố đang mở.

### 6.2. Bảng thiết bị

Các cột: mã máy, tên máy, IP, trạng thái, CPU, RAM, ổ đĩa và lần cập nhật cuối.

- Xanh: Online và bình thường.
- Vàng: Online nhưng có thông số vượt ngưỡng.
- Đỏ hoặc xám: Offline.

### 6.3. Bảng sự cố

Các cột: thời gian, thiết bị, loại sự cố, giá trị thực tế, ngưỡng và trạng thái.

Mọi cập nhật điều khiển JavaFX từ luồng mạng phải được chuyển qua JavaFX Application Thread.

## 7. Phát hiện và quản lý sự cố

Ngưỡng mặc định:

- CPU lớn hơn hoặc bằng 85%.
- RAM lớn hơn hoặc bằng 85%.
- Ổ đĩa lớn hơn hoặc bằng 90%.
- Không có heartbeat trong 15 giây: Offline.

Server chỉ duy trì một sự cố đang mở cho mỗi cặp `deviceId + incidentType`. Metrics vượt ngưỡng liên tiếp cập nhật sự cố hiện tại, không tạo bản ghi mới. Khi giá trị trở lại bình thường, sự cố chuyển sang `RESOLVED`. Lần vượt ngưỡng tiếp theo tạo một sự cố mới.

## 8. Mô hình dữ liệu

### 8.1. `devices`

| Trường | Ý nghĩa |
|---|---|
| `device_id` | Khóa chính, mã thiết bị |
| `device_name` | Tên hiển thị |
| `ip_address` | IP Client báo cáo hoặc Server quan sát |
| `os` | Hệ điều hành mô phỏng |
| `first_seen` | Lần đăng ký đầu tiên |
| `last_seen` | Lần nhận heartbeat gần nhất |
| `status` | `ONLINE` hoặc `OFFLINE` |

### 8.2. `latest_metrics`

| Trường | Ý nghĩa |
|---|---|
| `device_id` | Khóa chính và khóa ngoại đến thiết bị |
| `cpu_percent` | CPU mới nhất |
| `ram_percent` | RAM mới nhất |
| `disk_percent` | Ổ đĩa mới nhất |
| `collected_at` | Thời điểm thu thập |

### 8.3. `incidents`

| Trường | Ý nghĩa |
|---|---|
| `id` | Khóa chính tự tăng |
| `device_id` | Thiết bị phát sinh sự cố |
| `incident_type` | `HIGH_CPU`, `HIGH_RAM`, `DISK_FULL` hoặc `OFFLINE` |
| `actual_value` | Giá trị tại thời điểm phát hiện; có thể rỗng với Offline |
| `threshold_value` | Ngưỡng áp dụng |
| `detected_at` | Thời điểm phát hiện |
| `resolved_at` | Thời điểm khôi phục; rỗng nếu đang mở |
| `status` | `OPEN` hoặc `RESOLVED` |

## 9. Xử lý lỗi

- Sai IP/port: Client hiển thị lỗi kết nối và không treo giao diện.
- Trùng `deviceId`: Server trả `REGISTER_ACK` thất bại và đóng kết nối mới.
- Gửi metrics trước khi đăng ký: Server trả `ERROR`; vi phạm lặp lại dẫn đến đóng kết nối.
- JSON thiếu hoặc sai kiểu trường: Server trả `ERROR` và ghi log.
- Metrics ngoài khoảng 0–100: Server từ chối thông điệp.
- Mất kết nối: Server đóng socket, đánh dấu Offline và tiếp tục phục vụ phiên khác.
- Lỗi SQLite: Server ghi log, báo lỗi trên Dashboard nếu phù hợp và không làm dừng listener TCP.
- Client gửi quá nhanh: Server có thể bỏ qua metrics đến sớm hơn chu kỳ tối thiểu.
- Socket, stream và kết nối cơ sở dữ liệu phải được đóng an toàn.

## 10. Kiểm thử và tiêu chí chấp nhận

| Mã | Tình huống | Kết quả mong đợi |
|---|---|---|
| TC01 | Một Client kết nối đúng | Đăng ký thành công và hiển thị Online |
| TC02 | Ba Client kết nối đồng thời | Server quản lý đủ ba thiết bị |
| TC03 | Client gửi metrics hợp lệ | Dashboard cập nhật đúng số liệu |
| TC04 | CPU mô phỏng đạt ít nhất 85% | Tạo đúng một sự cố `HIGH_CPU` đang mở |
| TC05 | CPU trở lại dưới 85% | Sự cố chuyển thành `RESOLVED` |
| TC06 | Dừng Client | Thiết bị Offline sau tối đa 15 giây cộng sai số lịch chạy |
| TC07 | Client mới dùng `deviceId` đang kết nối | Server từ chối kết nối mới |
| TC08 | Client gửi JSON lỗi | Server không dừng và cô lập lỗi đúng phiên |
| TC09 | Khởi động lại Server | Thiết bị và lịch sử sự cố vẫn tồn tại trong SQLite |
| TC10 | Một Client lỗi | Các Client khác tiếp tục gửi dữ liệu bình thường |

## 11. Kế hoạch 5 tuần

### Tuần 1: Giao thức và kết nối

- Tạo cấu trúc dự án.
- Cài đặt codec độ dài + JSON.
- Kết nối, đăng ký và phục vụ nhiều Client ở chế độ console.

### Tuần 2: Heartbeat và metrics mô phỏng

- Tạo scheduler cho heartbeat và metrics.
- Quản lý Online/Offline.
- Hoàn thiện các chế độ sinh số liệu phục vụ demo.

### Tuần 3: Dashboard, SQLite và cảnh báo

- Xây dựng Dashboard JavaFX.
- Lưu thiết bị, số liệu mới nhất và sự cố.
- Hoàn thiện vòng đời mở/kết thúc sự cố.

### Tuần 4: Tích hợp và kiểm thử lỗi

- Chạy nhiều Client đồng thời.
- Kiểm thử mất kết nối, trùng ID, dữ liệu sai và lỗi từng phiên.
- Sửa lỗi đồng thời và cập nhật giao diện.

### Tuần 5: Ổn định và bảo vệ

- Đóng gói, viết hướng dẫn chạy và chuẩn bị dữ liệu demo.
- Hoàn thiện báo cáo, sơ đồ và video dự phòng.
- Không bổ sung chức năng mới trong tuần này.

## 12. Tiêu chí hoàn thành

Phiên bản giai đoạn đầu hoàn thành khi Server quản lý đồng thời ít nhất ba Client Simulator qua TCP, phát hiện Online/Offline, hiển thị metrics mới nhất, mở và kết thúc sự cố đúng ngưỡng, lưu dữ liệu cần thiết trong SQLite và vượt qua toàn bộ các kiểm thử TC01–TC10. Agent thật và điều khiển thiết bị không phải điều kiện hoàn thành.
