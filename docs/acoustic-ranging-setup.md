# Hướng Dẫn Cấu Hình & Vận Hành Định Vị Khoảng Cách Âm Học (Acoustic Ranging Setup & Operations)

Tài liệu hướng dẫn thiết lập, kiểm tra phần cứng âm thanh, hiệu chuẩn trễ và vận hành cơ chế đo khoảng cách vật lý bằng sóng siêu âm hai chiều (Two-Way Acoustic Ranging) tích hợp dự phòng Wi-Fi Direct trong hệ thống LTM.

---

## 1. Tổng Quan Kiến Trúc (Architecture Overview)

Hệ thống kết hợp đo khoảng cách vật lý lai (Hybrid Distance):
- **Kênh Âm Học Siêu Âm (Acoustic Ultrasonic - Chính)**: Sử dụng chirp tuyến tính tần số cao (mặc định 20.5 kHz – 22.5 kHz ở tần số lấy mẫu 96 kHz hoặc 18.5 kHz – 20 kHz ở 48 kHz). Phép đo hai chiều (Two-Way Ranging) triệt tiêu hoàn toàn sự lệch pha đồng hồ giữa Server và Agent.
- **Kênh Wi-Fi Direct (Dự phòng - Fallback)**: Dự phòng bằng mô hình suy hao Log-distance và bộ lọc Kalman 1D trên RSSI khi tín hiệu âm học bị che khuất, độ tin cậy < 90%, hoặc phần cứng không hỗ trợ.
- **Tuyệt đối không trung bình mù (No Fixed-Weight Averaging)**: Khoảng cách âm học và Wi-Fi Direct được phân tách rõ ràng; không trộn lẫn tỷ lệ cố định.

> [!IMPORTANT]
> **Cam Kết Quyền Riêng Tư (Privacy Invariant):**
> Hệ thống âm học **hoàn toàn không ghi âm hay lưu trữ giọng nói / âm thanh môi trường**. Dữ liệu âm thanh từ Microphone chỉ tồn tại trong bộ đệm RAM ngắn hạn (short-lived circular buffer vài trăm mili-giây) phục vụ thuật toán lọc phối hợp (Matched Filter), sau đó bị giải phóng ngay lập tức. Không có bất kỳ byte âm thanh thô nào được ghi xuống đĩa hoặc truyền qua mạng TCP.

> [!WARNING]
> **Trạng Thái Kiểm Định Thực Địa (Field Accuracy Status):**
> Cho đến khi hoàn tất đo kiểm thực tế trên hai laptop vật lý với bộ dữ liệu kiểm định độc lập (held-out validation dataset), hệ thống **tuyệt đối không tuyên bố đạt độ chính xác >90% trong phạm vi 5 cm trên thực địa**. Trạng thái hiện tại được ghi nhận là: `EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK`.

> [!NOTE]
> **Trạng thái transport Wi-Fi Direct (28/10/2026):** Hai laptop đã ghép cặp thành công, Windows helper báo `CONNECTED`, Linux đọc được station RSSI trên interface nhóm P2P và LAN hạ tầng vẫn hoạt động song song. Đây là xác nhận kết nối mạng trực tiếp, không phải xác nhận độ chính xác đo khoảng cách. Bootstrap WPS hiện được suy ra động bởi `scripts/setup-wifi-direct-server.sh`; không cần hardcode hostname, model, country, interface hay peer MAC trong mã nguồn.

---

## 2. Kiểm Tra Phần Cứng & Mixer Âm Thanh (Hardware & Audio Mixers)

### 2.1. Kiểm tra thiết bị âm thanh khả dụng trong Java Sound
Hệ thống sử dụng Java Sound API chuẩn (`javax.sound.sampled`). Để liệt kê danh sách Mixer trên máy:

```bash
# Liệt kê thiết bị âm thanh trên Linux
aplay -l   # Thiết bị phát (Playback)
arecord -l # Thiết bị thu (Capture / Mic)
```

Trong Java Sound:
- Để mặc định (rỗng `""`), hệ thống sẽ tự động chọn Audio Mixer mặc định của hệ điều hành.
- Có thể chỉ định chuỗi con tên Mixer trong file cấu hình (ví dụ: `playbackMixer=ALSA [default]` hoặc `captureMixer=PulseAudio`).

### 2.2. Kiểm định khả năng phần cứng (Audio Hardware Gate)
Trước khi kích hoạt đo khoảng cách, `AudioHardwareGate` thực hiện kiểm tra an toàn:
1. **Tần số lấy mẫu (Sample Rate)**: Tối thiểu 44.1 kHz (khuyến nghị 96 kHz để đáp ứng dải siêu âm 20.5 – 22.5 kHz; hoặc 48 kHz cho dải 18.5 – 20 kHz theo chuẩn Nyquist $f_s > 2 \cdot f_{\text{max}}$).
2. **Tuân thủ dải siêu âm (Ultrasonic Band Compliance)**: Tần số Chirp phải nằm ngoài ngưỡng nghe thông thường của người lớn (> 18 kHz).
3. **Rò rỉ âm thanh nghe được (Audible Leakage dBFS)**: Năng lượng âm trong dải nghe được (< 18 kHz) phải nhỏ hơn ngưỡng cho phép (mặc định $\le -40$ dBFS). Nếu thiết bị phát méo tiếng gây rò rỉ âm trầm nghe thấy, Gate sẽ từ chối phát.
4. **Tỷ số tín hiệu trên nhiễu (SNR)**: Tín hiệu phản hồi phải đạt tỷ số SNR $\ge 12$ dB.

Nếu phần cứng không đáp ứng, Agent sẽ công bố cờ `AUDIO_UNSUPPORTED` qua bản tin `RANGING_CAPABILITIES`. Server tự động chuyển sang cơ chế dự phòng Wi-Fi Direct.

---

## 3. Cấu Hình Hệ Thống (`acoustic-ranging.properties`)

Hệ thống tải cấu hình theo thứ tự ưu tiên:
1. Biến hệ thống Java: `-Dltm.acoustic.config=/đường_dẫn/acoustic-ranging.properties`
2. File cấu hình ngoài `acoustic-ranging.properties` tại thư mục chạy
3. Cấu hình mặc định trong classpath (`acoustic-ranging-defaults.properties`)

### Bảng tham số cấu hình:

| Tham số | Giá trị mặc định | Giải thích |
|---|---|---|
| `acoustic.ranging.enabled` | `true` | Bật/tắt tính năng đo khoảng cách âm học |
| `acoustic.ranging.playbackMixer` | `""` | Tên mixer phát âm (để trống = mặc định) |
| `acoustic.ranging.captureMixer` | `""` | Tên mixer thu mic (để trống = mặc định) |
| `acoustic.ranging.sampleRateHz` | `96000` | Tần số lấy mẫu (Hz) (khuyến nghị 96000 hoặc 48000) |
| `acoustic.ranging.chirpStartHz` | `20500` | Tần số bắt đầu chirp (Hz) |
| `acoustic.ranging.chirpEndHz` | `22500` | Tần số kết thúc chirp (Hz) |
| `acoustic.ranging.chirpDurationMs` | `20` | Thời lượng tín hiệu chirp (mili-giây) |
| `acoustic.ranging.playbackAmplitude` | `0.5` | Biên độ phát chuẩn hóa (0.0 đến 1.0) |
| `acoustic.ranging.responseDelayMs` | `120` | Độ trễ phản hồi cố định của Agent trước khi phát lại (ms) |
| `acoustic.ranging.measurementIntervalMs` | `300` | Chu kỳ đo lặp lại giữa Server và Client (ms) |
| `acoustic.ranging.maxDistanceMeters` | `10.0` | Khoảng cách vật lý tối đa được chấp nhận (mét) |
| `acoustic.ranging.ambientTemperatureCelsius` | `20.0` | Nhiệt độ môi trường phòng (°C) để tính vận tốc âm thanh |
| `acoustic.ranging.calibrationOffsetMillis` | `0.0` | Độ trễ bù phần cứng (ms) thu được sau hiệu chuẩn |
| `acoustic.ranging.confidenceThreshold` | `90.0` | Ngưỡng điểm tin cậy tối thiểu (%) để chấp nhận kết quả |

---

## 4. Quy Trình Hiệu Chuẩn Phần Cứng & Đo Kiểm Thực Địa (Calibration & Field Benchmark)

### 4.1. Vận tốc âm thanh theo nhiệt độ
Vận tốc âm thanh trong không khí phụ thuộc vào nhiệt độ môi trường:
$$v = 331.3 \cdot \sqrt{1 + \frac{T}{273.15}} \quad (\text{m/s})$$
Ví dụ:
- Ở $20^\circ\text{C}$: $v \approx 343.2$ m/s
- Ở $25^\circ\text{C}$: $v \approx 346.1$ m/s
Cần cập nhật `acoustic.ranging.ambientTemperatureCelsius` sát với nhiệt độ phòng đo.

### 4.2. Hiệu chuẩn độ trễ phần cứng (Hardware Latency Calibration)
1. Đặt 2 laptop Server và Agent sát nhau sao cho khoảng cách giữa Micro và Loa bằng 0 cm (hoặc đo tại khoảng cách chuẩn 0.5 m).
2. Chạy phiên đo hiệu chuẩn và quan sát độ trễ đo được $t_{\text{raw}}$.
3. Tính độ trễ phần cứng bù trừ:
   $$t_{\text{offset}} = t_{\text{raw}} - t_{\text{responseDelay}} - \frac{2 \cdot d_{\text{true}}}{v}$$
4. Nhập giá trị $t_{\text{offset}}$ (tính bằng mili-giây) vào tham số `acoustic.ranging.calibrationOffsetMillis`.

### 4.3. Quy trình kiểm định độc lập trên hai laptop (Held-out Evaluation)
1. Bố trí thước đo khoảng cách thực địa tại các mốc chuẩn: **0.5 m, 1.0 m, 2.0 m, 3.0 m, 5.0 m**.
2. Thực hiện 2 phiên đo tách biệt hoàn toàn:
   - **Phiên 1 - Hiệu chuẩn (CALIBRATION)**: Dùng để tinh chỉnh `calibrationOffsetMillis` và kiểm tra mức âm lượng.
   - **Phiên 2 - Kiểm định (VALIDATION)**: Tập dữ liệu độc lập (held-out), không được dùng để chỉnh tham số.
3. Xuất kết quả đo ra file CSV theo định dạng:
   ```csv
   sample_id,session_type,true_distance_m,measured_distance_m,confidence_score,snr_db,peak_ratio
   s1,VALIDATION,0.5,0.51,96.0,26.0,5.0
   s2,VALIDATION,1.0,1.02,94.0,24.0,4.8
   ...
   ```
4. Chạy bộ kiểm thử chấp nhận `RangingAcceptanceDatasetTest` trên Server:
   - Tỷ lệ mẫu đạt trong sai số cho phép ($\le 5\text{ cm}$) phải đạt $\ge 90\%$ trên toàn bộ các mốc khoảng cách.
   - Khi và chỉ khi dữ liệu xuất phát từ đo kiểm thực tế trên hai laptop vật lý và đạt tỷ lệ này, chỉ số độ chính xác >90% mới được công nhận chính thức.

---

## 5. Cơ Chế Dung Hợp & Dự Phòng Realtime (Realtime Fusion & Fallback)

Trên giao diện điều khiển Server Dashboard:
- Khi tín hiệu âm học đạt tin cậy $\ge 90\%$: Nhãn khoảng cách hiển thị định dạng `~X.XX m (XXX cm) [Âm học, tin cậy XX%]`.
- Khi tín hiệu âm học bị che chắn, suy giảm, hoặc phần cứng không hỗ trợ: Tự động chuyển hiển thị `WIFI_FALLBACK (...)` với khoảng cách ước tính từ Wi-Fi Direct Kalman Filter.
- Badge trạng thái client thể hiện nguồn đo tức thời: `📍 ~X.XX m [Âm học]` hoặc `📍 ~X.XX m | RTT XX ms | -XX dBm`.
