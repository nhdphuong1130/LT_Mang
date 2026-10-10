# Kịch Bản Trình Diễn Hệ Thống (Classroom Demo Script)

Tài liệu này hướng dẫn kịch bản thực nghiệm trực tiếp hệ thống **LAN Device Monitoring & Backup** trong buổi báo cáo hoặc kiểm thử thực tế, bao gồm 2 Agent (Windows và Linux) kết nối đến 1 Server.

---

## 1. Chuẩn Bị Trước Buổi Trình Diễn (Preparation)

1. **Thiết bị**:
   - **Máy A (Server)**: Máy chủ chạy Linux hoặc Windows (IP ví dụ: `192.168.1.100`).
   - **Máy B (Agent 1)**: Máy trạm Windows (hoặc máy ảo Windows).
   - **Máy C (Agent 2)**: Máy trạm Linux (hoặc terminal/máy ảo Linux thứ 2).
2. **Kiểm tra kết nối mạng**:
   - Từ Máy B và C: `ping 192.168.1.100` thành công.
   - Kiểm tra mở port 5050 và 5051 trên Máy A.
3. **Mã nguồn & Đóng gói**:
   - Chạy `./mvnw clean package` (hoặc `mvnw.cmd clean package`) trên các máy để đảm bảo file JAR đã sẵn sàng.

---

## 2. Các Bước Thực Hiện Demo (Demo Steps)

### Phần 1: Khởi động Server & Đăng ký Quản trị viên
1. Trên Máy A, mở terminal và chạy:
   ```bash
   ./scripts/run-server.sh
   # (Trên Windows: scripts\run-server.bat)
   ```
2. Giao diện Server Dashboard xuất hiện:
   - Vì là lần đầu khởi chạy, màn hình yêu cầu tạo tài khoản Admin.
   - Nhập: Username = `admin`, Password = `AdminPassword123!` (độ dài >= 12 ký tự).
   - Bấm **Tạo tài khoản**.
   - Đăng nhập vào Dashboard.

### Phần 2: Cấp phát Thiết bị (Device Provisioning)
1. Trên Dashboard của Server, vào tab **Thiết bị**.
2. Cấp phát **Agent 1**:
   - Device ID: `pc-windows-01`
   - Tên thiết bị: `Phong Ke Toan - Win`
   - Hệ điều hành: `Windows`
   - Bấm **Cấp phát**. Sao chép Token sinh ra (ví dụ: `T1_ABC...`).
3. Cấp phát **Agent 2**:
   - Device ID: `pc-linux-02`
   - Tên thiết bị: `Phong Ky Thuat - Linux`
   - Hệ điều hành: `Linux`
   - Bấm **Cấp phát**. Sao chép Token sinh ra (ví dụ: `T2_XYZ...`).
4. **Điểm nhấn trình diễn**: Hai thiết bị đang ở trạng thái `OFFLINE` trong danh sách.

### Phần 3: Kết nối 2 Agent & Theo dõi Chỉ số Phần cứng (Metrics)
1. **Khởi động Agent 1 (Máy Windows)**:
   - Chạy `scripts\run-agent.bat`.
   - Nhập:
     - Server Host: `192.168.1.100` (IP Server)
     - Control Port: `5050`, File Port: `5051`
     - Device ID: `pc-windows-01`
     - Device Token: `T1_ABC...`
     - Thư mục bảo vệ: Chọn thư mục `D:\ProtectedFolder`
   - Bấm **Bắt đầu giám sát & sao lưu**.
   - Trạng thái Agent chuyển sang `ONLINE`.
2. **Khởi động Agent 2 (Máy Linux)**:
   - Chạy `./scripts/run-agent.sh`.
   - Nhập thông tin tương ứng của `pc-linux-02` và thư mục bảo vệ `/home/user/Protected`.
   - Bấm **Bắt đầu giám sát & sao lưu**.
3. **Quan sát trên Server Dashboard**:
   - Cả hai máy đồng thời chuyển sang trạng thái `ONLINE` màu xanh.
   - Bấm vào từng thiết bị: Các chỉ số CPU, RAM, Dung lượng ổ cứng, Pin, Nhiệt độ (hoặc `N/A` nếu phần cứng không có cảm biến) cập nhật liên tục mỗi 5 giây.
   - **Điểm nhấn**: Kênh điều khiển duy trì Heartbeat ổn định, quản lý độc lập 2 máy trạm cùng lúc.

### Phần 4: Kích hoạt Cảnh báo Nguy cơ (Forced Threshold Alert)
1. **Mô phỏng tải RAM cao trên Agent**:
   - Chạy một script hoặc mở các ứng dụng để RAM vượt ngưỡng 90% (hoặc giả lập trong kiểm thử).
   - Duy trì liên tục trong **15 giây**.
2. **Kết quả quan sát**:
   - Trên màn hình Agent: Xuất hiện hộp thoại/thông báo cảnh báo màu vàng:
     > *"Cảnh báo nguy cơ: RAM 92.5%. Vui lòng lưu lại công việc của bạn."*
   - Trên Server Dashboard: Chuyển sang tab **Sự cố** (Incidents), xuất hiện ngay một bản ghi mới với loại `HIGH_RAM`, trạng thái `OPEN`.
3. **Hạ tải**:
   - Tắt ứng dụng tiêu tốn RAM.
   - Chỉ số RAM hạ xuống dưới 90%.
   - Sự cố trên Server tự động chuyển sang trạng thái `RESOLVED` kèm mốc thời gian kết thúc.

### Phần 5: Tự Động Sao Lưu Đa Phiên Bản (2 File Versions)
1. **Tạo phiên bản 1**:
   - Trên Agent 1, trong thư mục bảo vệ `ProtectedFolder`, tạo file `bao-cao.txt` với nội dung:
     ```
     Báo cáo tài chính Quý 1 - Bản thảo sơ bộ ngày 10/10/2026.
     ```
   - Chờ 3 giây (thời gian ổn định file).
   - Agent tự động quét thấy file -> đưa vào hàng đợi -> upload lên Server qua cổng 5051.
   - Trên Server Dashboard: Tab **Sao lưu & Phiên bản** xuất hiện file `bao-cao.txt` với **Phiên bản 1**, kèm mã băm SHA-256.
2. **Chỉnh sửa tạo phiên bản 2**:
   - Mở file `bao-cao.txt` trên Agent 1, sửa nội dung thành:
     ```
     Báo cáo tài chính Quý 1 - Đã kiểm toán chính thức và ký duyệt ngày 11/10/2026.
     ```
   - Lưu file. Sau 3 giây, Agent phát hiện thay đổi và upload phiên bản mới.
   - Trên Server Dashboard: Mục `bao-cao.txt` hiển thị danh sách **2 phiên bản** khác nhau với SHA-256 riêng biệt.

### Phần 6: Kiểm tra Tính Bền Vững (Ngắt Mạng Khi Đang Sao Lưu)
1. Trên Agent 2, chuẩn bị sao lưu một file tài liệu `tailieu-lon.pdf` (khoảng 10-20 MB).
2. Khi file bắt đầu sao lưu hoặc rút dây mạng LAN / tắt tạm kết nối mạng:
   - Kết nối truyền file bị gián đoạn.
3. **Kết quả quan sát**:
   - Trên Server: Không xuất hiện phiên bản rác hay file hỏng (file tạm `.tmp` tự động được dọn dẹp sạch sẽ).
   - Trên Agent 2: File được giữ nguyên trong hàng đợi SQLite nội bộ với trạng thái `RETRY`.
4. **Bật lại kết nối mạng**:
   - Agent 2 phát hiện mạng phục hồi, tự động thử lại (Retry) và upload thành công.
   - Server ghi nhận phiên bản hoàn chỉnh với mã SHA-256 chuẩn xác.

### Phần 7: Khôi Phục File An Toàn & Chứng Minh Không Ghi Đè (Admin Restore)
1. **Yêu cầu khôi phục**:
   - Trên Server Dashboard, chọn thiết bị `pc-windows-01` và file `bao-cao.txt`.
   - Chọn **Phiên bản 1** (bản sơ bộ).
   - Bấm nút **Khôi phục về thiết bị** (Restore).
2. **Tiến trình khôi phục**:
   - Server sinh Token sử dụng một lần và gửi lệnh điều khiển tới Agent 1 qua cổng 5050.
   - Agent 1 mở kết nối tới cổng 5051 của Server, tải phiên bản 1 về vùng đệm tạm, kiểm tra mã băm SHA-256.
   - Sau khi kiểm tra thành công, Agent xuất file vào thư mục:
     ```
     ProtectedFolder/Recovered/<yyyyMMddTHHmmssZ>/bao-cao.txt (restored-...)
     ```
   - Trạng thái công việc trên Server cập nhật: `COMPLETED`.
3. **Chứng minh không ghi đè dữ liệu gốc (CRITICAL)**:
   - Mở file gốc `ProtectedFolder/bao-cao.txt`: Nội dung vẫn là **Phiên bản 2** (*"Đã kiểm toán chính thức"*). **Dữ liệu hiện tại không hề bị mất hay ghi đè!**
   - Mở file trong thư mục `ProtectedFolder/Recovered/...`: Nội dung đúng chính xác là **Phiên bản 1** (*"Bản thảo sơ bộ"*).
   - Đối chiếu SHA-256: Mã băm của file khôi phục trùng khớp 100% với mã băm của Phiên bản 1 trên Server.

---

## 3. Bảng Đối Chiếu Ca Kiểm Thử (TC01 – TC17 Matrix)

| Mã TC | Nội Dung Ca Kiểm Thử | Vị Trí Chứng Minh Trong Demo | Kết Quả |
|---|---|---|---|
| **TC01** | Agent Windows đăng ký | Phần 3: Agent 1 kết nối | ĐẠT |
| **TC02** | Agent Linux đăng ký | Phần 3: Agent 2 kết nối | ĐẠT |
| **TC03** | Nhiều Agent đồng thời | Phần 3: Cả 2 Agent Online độc lập | ĐẠT |
| **TC04** | CPU/RAM vượt ngưỡng đủ lâu | Phần 4: Giả lập RAM > 90% 15s | ĐẠT |
| **TC05** | Không có cảm biến nhiệt | Phần 3: Hiển thị `N/A`, không báo lỗi | ĐẠT |
| **TC06** | File mới hoặc thay đổi | Phần 5: Phiên bản 1 và Phiên bản 2 | ĐẠT |
| **TC07** | File không thay đổi | Lưu lại cùng nội dung -> Không upload lặp lại | ĐẠT |
| **TC08** | File tạm hoặc > 100 MB | Đặt file `.tmp` -> Watcher bỏ qua đúng quy tắc | ĐẠT |
| **TC09** | Mất mạng giữa upload | Phần 6: Ngắt mạng -> Agent tự retry | ĐẠT |
| **TC10** | Checksum sai | Tích hợp trong E2E Test: Server từ chối payload | ĐẠT |
| **TC11** | Tạo phiên bản thứ 6 | Tích hợp trong Unit Test: Prune giữ 5 bản gần nhất | ĐẠT |
| **TC12** | Người chưa đăng nhập | Không thể gọi API khôi phục nếu chưa login | ĐẠT |
| **TC13** | Agent Offline khi restore | Đưa Agent offline -> Restore job giữ `PENDING` | ĐẠT |
| **TC14** | Agent Online trở lại | Bật Agent -> Tự động nhận job và hoàn thành | ĐẠT |
| **TC15** | Khôi phục an toàn | Phần 7: File vào `Recovered/`, không ghi đè | ĐẠT |
| **TC16** | Đường dẫn `../` | Tích hợp trong E2E Test: Chặn path traversal | ĐẠT |
| **TC17** | Một Agent lỗi | E2E Test: Agent hỏng không ảnh hưởng Agent tốt | ĐẠT |
