# Hệ Thống Giám Sát Thiết Bị & Tự Động Sao Lưu File Qua Mạng LAN

Hệ thống ứng dụng mạng phân tán phục vụ giám sát tình trạng phần cứng, cảnh báo rủi ro tức thời và tự động sao lưu / khôi phục dữ liệu an toàn cho các máy trạm trong mạng cục bộ (LAN).

---

## 1. Yêu Cầu Môi Trường (Prerequisites)

- **Java Development Kit (JDK)**: Phiên bản **Java 21 LTS** trở lên.
- **Maven Wrapper**: Đã tích hợp sẵn trong mã nguồn (`./mvnw` cho Linux/macOS và `mvnw.cmd` cho Windows). Không yêu cầu cài đặt Maven toàn cục.
- **Môi trường đồ họa**: Màn hình hỗ trợ hiển thị giao diện JavaFX (X11 / Wayland trên Linux, Desktop trên Windows).
- **Mạng**: Các máy Agent và Server nằm trong cùng dải mạng LAN (hoặc chạy trên `127.0.0.1` khi chạy thử nghiệm trên một máy).

---

## 2. Cổng Mạng & Cấu Hình Tường Lửa (Ports & Firewall)

Hệ thống sử dụng **hai kênh TCP độc lập** tách biệt hoàn toàn luồng điều khiển và truyền tải file:

| Cổng TCP | Giao Thức / Mục Đích | Đặc Điểm |
|---|---|---|
| **5050** | Kênh Điều Khiển (Control Channel) | Kết nối liên tục (persistent TCP), xác thực Agent, gửi Heartbeat (5s), gửi Metrics (CPU, RAM, Disk, Nhiệt độ, Pin), cảnh báo sự cố (Risk Alerts), gửi lệnh Restore. |
| **5051** | Kênh Truyền File (File Transfer Channel) | Kết nối ngắn hạn (short-lived TCP), truyền tải nhị phân có header và xác thực token, phục vụ upload backup (tối đa 100 MB/file) và download restore. |

### Cấu hình tường lửa trên máy Server:
- **Linux (ufw)**:
  ```bash
  sudo ufw allow 5050/tcp comment "LTM Control Port"
  sudo ufw allow 5051/tcp comment "LTM File Transfer Port"
  ```
- **Windows**:
  Mở *Windows Defender Firewall with Advanced Security* -> *Inbound Rules* -> Thêm Rule cho TCP port `5050` và `5051`.

---

## 3. Xác Định Địa Chỉ IP Server (Server IP)

Trước khi cấu hình Agent, hãy kiểm tra địa chỉ IP LAN của máy Server:
- **Linux**:
  ```bash
  ip -br addr show
  # Hoặc: hostname -I
  ```
- **Windows**:
  ```cmd
  ipconfig
  ```
Tìm địa chỉ IPv4 của card mạng LAN (ví dụ: `192.168.1.100`).

Mặc định Server lắng nghe trên mọi giao diện mạng (`0.0.0.0`). Có thể tùy chỉnh qua biến hệ thống:
```bash
-Dltm.server.bind=192.168.1.100 -Dltm.server.controlPort=5050 -Dltm.server.filePort=5051
```

---

## 4. Hướng Dẫn Vận Hành & Khởi Động (Run Commands)

### 4.1. Đóng gói ứng dụng (Build Packaging)
Thực thi lệnh sau ở thư mục gốc dự án:
- **Linux / macOS**:
  ```bash
  ./mvnw clean package
  ```
- **Windows**:
  ```cmd
  mvnw.cmd clean package
  ```
Sau khi build, các file jar thực thi độc lập (executable uber-jar) sẽ nằm tại:
- `server/target/server-1.0-SNAPSHOT-executable.jar`
- `agent/target/agent-1.0-SNAPSHOT-executable.jar`

### 4.2. Khởi chạy Server
- **Linux**:
  ```bash
  ./scripts/run-server.sh
  # Hoặc trực tiếp:
  java -jar server/target/server-1.0-SNAPSHOT-executable.jar
  ```
- **Windows**:
  ```cmd
  scripts\run-server.bat
  :: Hoặc trực tiếp:
  java -jar server\target\server-1.0-SNAPSHOT-executable.jar
  ```

### 4.3. Khởi chạy Agent
- **Linux**:
  ```bash
  ./scripts/run-agent.sh
  # Hoặc trực tiếp:
  java -jar agent/target/agent-1.0-SNAPSHOT-executable.jar
  ```
- **Windows**:
  ```cmd
  scripts\run-agent.bat
  :: Hoặc trực tiếp:
  java -jar agent\target\agent-1.0-SNAPSHOT-executable.jar
  ```

---

## 5. Quy Trình Thiết Lập Ban Đầu (Initial Setup)

### Bước 1: Khởi tạo tài khoản Quản trị viên (Initial Admin Creation)
1. Khi Server khởi chạy lần đầu tiên và cơ sở dữ liệu SQLite chưa có tài khoản admin, màn hình đăng nhập sẽ hiển thị form tạo tài khoản ban đầu.
2. Nhập tên tài khoản (Username) và Mật khẩu (Password).
   - **Quy tắc bảo mật**: Mật khẩu phải có độ dài **tối thiểu 12 ký tự**. Mật khẩu được băm an toàn bằng **bcrypt (cost factor 12)**.
3. Bấm **Tạo tài khoản** và đăng nhập vào Dashboard quản trị.

### Bước 2: Cấp phát thiết bị (Device Provisioning)
1. Trên giao diện Server Dashboard, chuyển sang tab **Thiết bị** (Devices).
2. Nhập thông tin thiết bị:
   - **Mã thiết bị (Device ID)**: Chuỗi định danh từ 1 đến 64 ký tự (chữ cái, chữ số, `.`, `_`, `-`, ví dụ: `ws-it-01`).
   - **Tên hiển thị (Name)**: Tên người dùng hoặc phòng ban (ví dụ: `May Ke Toan 01`).
   - **Hệ điều hành (OS)**: Chọn `Windows` hoặc `Linux`.
3. Bấm **Cấp phát thiết bị** (Provision).
4. Hệ thống sẽ sinh ra một **Token bảo mật** ngẫu nhiên 43 ký tự URL-safe Base64 (được lưu băm SHA-256 + salt trong SQLite Server). Sao chép Token này để cấu hình trên Agent.

### Bước 3: Cấu hình Agent (Agent Configuration)
1. Mở giao diện ứng dụng Agent trên máy trạm.
2. Điền các tham số kết nối:
   - **Địa chỉ máy chủ (Server Host)**: Địa chỉ IP của máy Server (ví dụ: `192.168.1.100` hoặc `127.0.0.1` nếu test cục bộ).
   - **Cổng điều khiển (Control Port)**: `5050`.
   - **Cổng truyền file (File Port)**: `5051`.
   - **Mã thiết bị (Device ID)**: Đúng mã đã cấp phát ở Bước 2.
   - **Token thiết bị (Device Token)**: Đúng chuỗi Token nhận được ở Bước 2.
   - **Thư mục bảo vệ (Protected Folder)**: Chọn thư mục trên máy trạm cần tự động theo dõi và sao lưu (ví dụ: `/home/user/Documents/Protected` hoặc `D:\ProtectedFolder`).
3. Bấm **Bắt đầu giám sát & sao lưu**. Trạng thái kết nối sẽ chuyển sang **Đang kết nối / Đã đăng ký (Online)**. Cấu hình được tự động lưu vào `~/.ltm-agent/agent-settings.json`.

---

## 6. Quy Tắc Giám Sát, Sao Lưu & Khôi Phục

### 6.1. Giám sát phần cứng & Cảnh báo nguy cơ
- Agent thu thập số liệu phần cứng mỗi 5 giây qua thư viện OSHI (CPU, RAM, Ổ đĩa, Pin, Nhiệt độ nếu có).
- **Cảnh báo cục bộ và gửi sự cố về Server**:
  - **CPU, RAM hoặc ổ đĩa đạt từ 80%**: Mở cảnh báo ngay lập tức.
  - **Nhiệt độ đạt từ 80°C**: Mở cảnh báo ngay lập tức.
  - **Pin còn tối đa 20% và không cắm sạc**: Mở cảnh báo ngay lập tức.
  - Khi chỉ số hạ về mức an toàn, sự cố tự động chuyển sang trạng thái `RESOLVED`.
  - Khi không có cảm biến nhiệt độ phần cứng, hệ thống hiển thị `N/A`, không báo lỗi giả.

### 6.2. Quy tắc Thư mục Bảo vệ & Lọc File
- Theo dõi thư mục bảo vệ đệ quy bằng `WatchService`.
- **Chống ghi dồn (Coalescing)**: Gom sự kiện thay đổi trong khoảng 250ms.
- **Kiểm tra độ ổn định file (File Stability Check)**: File phải duy trì dung lượng và thời gian sửa đổi không đổi trong 3 giây trước khi đưa vào hàng đợi sao lưu.
- **Giới hạn dung lượng**: Bỏ qua các file vượt quá **100 MiB**.
- **Loại trừ**: Bỏ qua file tạm (`.tmp`, `.part`), file ẩn, file hệ thống, và toàn bộ thư mục `Recovered/`.
- **An toàn đường dẫn**: Ngăn chặn path traversal (`../`) và ngăn chặn tấn công tráo đổi liên kết tượng trưng (symlink).

### 6.3. Sao lưu & Lưu giữ phiên bản (Backup & Version Retention)
- Mỗi file được kiểm tra mã băm SHA-256. Nếu nội dung không thay đổi, hệ thống không upload lặp lại (`UNCHANGED`).
- Server lưu trữ tối đa **5 phiên bản gần nhất** cho mỗi file. Khi upload phiên bản thứ 6, phiên bản cũ nhất sẽ được tự động xóa an toàn khỏi đĩa cứng và cơ sở dữ liệu.
- Quá trình upload bị gián đoạn (mất mạng, ngắt kết nối) sẽ không tạo phiên bản lỗi trên Server; file tạm `.tmp` bị dọn dẹp; Agent giữ mục trong hàng đợi và tự động thử lại khi có mạng trở lại.

### 6.4. Khôi phục an toàn (Safe Restore)
- **Quyền hạn**: Chỉ Quản trị viên đã đăng nhập trên Server Dashboard mới có quyền gửi yêu cầu khôi phục file.
- **Xác thực**: Server sinh mã token một lần (One-Time Token, thời hạn 10 phút) và gửi thông báo qua kênh điều khiển đến Agent.
- **Không ghi đè dữ liệu gốc**: Agent tải file về phân vùng tạm, kiểm tra mã băm SHA-256 khớp tuyệt đối với Server, sau đó liên kết an toàn vào:
  ```
  <ProtectedRoot>/Recovered/<yyyyMMddTHHmmssZ>/<relativePath> (restored-<random>)
  ```
  File gốc của người dùng tại thư mục bảo vệ **không bao giờ bị ghi đè**.

---

## 7. Giới Hạn Bảo Mật & Lưu Ý Kỹ Thuật (Known Security Limits)

1. **Môi trường triển khai LAN**: Hệ thống thiết kế cho mạng cục bộ; kênh TCP truyền tải nội bộ không mã hóa TLS lớp truyền tải mà sử dụng xác thực khóa băm SHA-256, token định danh và bcrypt. Trong môi trường mạng công cộng mở, cần thiết lập VPN hoặc đường hầm SSH/TLS.
2. **Quyền sở hữu tập tin cục bộ**: Cơ chế chống tráo đổi symlink bảo vệ ứng dụng khỏi các hành vi chuyển hướng thư mục, nhưng giả định rằng tiến trình cục bộ độc hại không có đặc quyền root/superuser ngang quyền sở hữu file với Agent.
3. **Cảm biến nhiệt độ và pin**: Một số máy bàn hoặc máy ảo không có chip đo nhiệt độ hoặc pin; các giá trị này sẽ hiển thị `N/A` theo đúng đặc tả kỹ thuật.

---

## 8. Đo khoảng cách Server–Client bằng Wi-Fi Direct

Tính năng này dùng hai liên kết song song:

- **LAN hiện tại** tiếp tục truyền heartbeat, metrics, dxdiag và file qua cổng 5050/5051.
- **Wi-Fi Direct** chỉ tạo liên kết radio trực tiếp giữa laptop Server và Client để Server đọc RSSI của đúng Client. Router không phải tâm đo.

RTT của LAN chỉ được hiển thị như thông tin độ trễ; hệ thống không đổi RTT thành mét. Khoảng cách vật lý chỉ xuất hiện khi Server đối chiếu được MAC P2P mà Agent báo với một station trên interface nhóm `p2p-…` của Linux.

### 8.1. Kiểm tra khả năng phần cứng

Trên Linux Server:

```bash
./scripts/check-wifi-direct-linux.sh
```

Trên Windows Client, mở PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-wifi-direct-windows.ps1
```

Kết quả Windows phải có Microsoft Wi-Fi Direct Virtual Adapter. Adapter ở trạng thái `Disconnected` trước khi ghép cặp là bình thường.

### 8.2. Ghép cặp và khởi tạo lại liên kết

Build helper Windows theo [hướng dẫn riêng](tools/wifi-direct-windows/README.md). Trên Windows phải chạy listener trước và giữ cửa sổ mở cho đến khi xuất hiện `LISTENING`:

```powershell
.\WifiDirectAgentHelper.exe --listen
```

Trên Linux, trước tiên chạy dry-run với interface và MAC P2P lấy từ chính hai máy đang dùng:

```bash
./scripts/setup-wifi-direct-server.sh <wifi-interface> <windows-p2p-mac> ltm-wifi-direct-distance
```

Sau khi kiểm tra đúng interface và MAC P2P của Windows, thêm `--apply`:

```bash
./scripts/setup-wifi-direct-server.sh <wifi-interface> <windows-p2p-mac> ltm-wifi-direct-distance --apply
```

Không dùng MAC của card Wi-Fi vật lý. Lấy MAC của **Microsoft Wi-Fi Direct Virtual Adapter** bằng `Get-NetAdapter -IncludeHidden` và đối chiếu peer Linux phát hiện được.

Script tự lấy hostname, hãng/model máy và regulatory country tại thời điểm chạy, sau đó cấu hình `wpa_supplicant` cho WPS push-button trước khi tạo profile. Vì vậy không cần chạy thủ công chuỗi `wpa_cli set ...` sau khi interface hoặc driver được khởi tạo lại. Các giá trị nhận dạng máy, interface, peer MAC và country không được hardcode trong script. Ở chế độ `--apply`, script có thể yêu cầu `sudo` để truy cập control socket của `wpa_supplicant`.

Script chỉ quản lý profile có tiền tố `ltm-wifi-direct-`. Nếu P2P làm mất LAN, script tự gỡ profile vừa tạo và bật lại profile LAN. Dừng liên kết bằng:

```bash
./scripts/stop-wifi-direct-server.sh ltm-wifi-direct-distance
```

### 8.3. Xác nhận đúng nguồn RSSI

Giữ Agent và helper chạy trên Windows, sau đó kiểm tra trên Server:

```bash
iw dev
iw dev <p2p-group-interface> station dump
```

MAC station phải trùng `localP2pMac` do Agent báo. Dashboard chỉ hiển thị mét khi nguồn là `WIFI_DIRECT_SERVER_STATION`; RSSI của SSID/router cũ bị bỏ qua.

Kết nối transport được xác nhận khi Windows báo `CONNECTED`, Linux có interface nhóm `p2p-…`, station ở trạng thái `authorized/associated`, và hai đầu trao đổi được gói tin trên subnet P2P. Việc này chỉ xác nhận liên kết trực tiếp; không phải bằng chứng cho độ chính xác khoảng cách.

### 8.4. Hiệu chuẩn có bằng chứng

1. Chọn Client trên Dashboard và bấm **Hiệu chuẩn khoảng cách**.
2. Đặt khoảng cách thực tế lần lượt tại 0,5 m, 1 m, 2 m, 3 m và 5 m.
3. Mỗi mốc bấm thu đủ 100 mẫu mới. Hệ thống loại mẫu cũ, trùng, sai Client hoặc sai nguồn.
4. Xuất CSV để lưu bằng chứng thực nghiệm.
5. Kiểm tra các giá trị `A`, `n`, MAE và RMSE rồi bấm kích hoạt.

Khi chưa hiệu chuẩn, công thức Log-Distance vẫn dùng giá trị tham khảo trung bình `A=-50 dBm`, `n=3.0`, nhưng Dashboard ghi rõ `default-unverified`. RSSI chịu ảnh hưởng bởi hướng anten, vật cản, phản xạ đa đường và môi trường, vì vậy kết quả luôn là ước lượng chứ không phải phép đo chính xác như thước hoặc UWB.

### 8.5. Ý nghĩa trạng thái

| Trạng thái | Ý nghĩa |
|---|---|
| `CONNECTED` | MAC Agent đã khớp station trên nhóm Wi-Fi Direct và mẫu còn mới. |
| `PAIRING` | Đang ghép cặp lần đầu. |
| `RECONNECTING` | Adapter tồn tại nhưng liên kết trực tiếp đang kết nối lại. |
| `UNAVAILABLE` | Máy hoặc driver không cung cấp Wi-Fi Direct. |
| `WRONG_SIGNAL_SOURCE` | Server chưa thấy MAC Client trong nhóm P2P; không được phép hiện mét. |
| `STALE_SIGNAL` | Định danh hoặc mẫu RSSI đã quá hạn. |
| `AGENT_UPDATE_REQUIRED` | Agent cũ chưa gửi định danh/capability Wi-Fi Direct; cần chép lại JAR mới. |

---

## 9. Đo khoảng cách vật lý bằng Sóng Siêu Âm Lai (Hybrid Acoustic Ranging)

Hệ thống bổ sung cơ chế đo khoảng cách vật lý hai chiều bằng sóng âm dải siêu âm (Two-Way Ultrasonic Ranging) giữa Server và Agent laptop:
- **Kênh Âm Học (Chính)**: Sử dụng chirp tuyến tính dải tần 20.5 kHz – 22.5 kHz (hoặc 18.5 kHz – 20 kHz tùy sample rate). Độ trễ hai chiều loại bỏ sự lệch pha đồng hồ mà không cần đồng bộ NTP micro-giây.
- **Dự phòng Wi-Fi Direct (Fallback)**: Khi tín hiệu âm học bị che khuất, độ tin cậy < 90%, hoặc phần cứng không đáp ứng, hệ thống tự động chuyển sang mô hình Wi-Fi Direct RSSI Kalman. Tuyệt đối không trung bình mù hai nguồn dữ liệu.
- **Bảo mật & Quyền riêng tư**: **Hoàn toàn không ghi âm hay lưu trữ âm thanh môi trường**. Dữ liệu âm thanh chỉ tồn tại trong bộ đệm RAM vài trăm mili-giây cho phép lọc phối hợp (Matched Filter) rồi tự động giải phóng. Không có bất kỳ byte âm thanh thô nào được gửi qua mạng.
- **Trạng thái kiểm định thực tế**: Mục tiêu độ chính xác >90% trong sai số 5 cm được kiểm thử bằng bộ dữ liệu độc lập (held-out validation dataset) trên các mốc 0.5m, 1m, 2m, 3m, 5m. Hiện tại khi chưa hoàn tất đo kiểm thực địa trên hai laptop vật lý, trạng thái kỹ thuật được ghi nhận rõ ràng là **chưa thể tuyên bố đạt độ chính xác >90% trên thực địa** (`EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK`).

Chi tiết cấu hình, danh sách Audio Mixer, kiểm định phần cứng và quy trình hiệu chuẩn xem tại: [Hướng dẫn cấu hình âm học](docs/acoustic-ranging-setup.md).
