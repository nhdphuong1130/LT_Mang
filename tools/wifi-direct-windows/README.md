# Wi-Fi Direct Agent Helper (Windows)

Tiện ích này dùng API WinRT `Windows.Devices.WiFiDirect` để tìm, ghép cặp và giữ phiên kết nối trực tiếp tới Linux Server. Nó không thay thế kết nối LAN của ứng dụng; LAN vẫn vận chuyển metrics, dxdiag và file.

## Build và chạy

```powershell
dotnet publish .\WifiDirectAgentHelper.csproj -c Release -f net10.0-windows10.0.19041.0 -r win-x64 --self-contained true -p:PublishSingleFile=true
.\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish\WifiDirectAgentHelper.exe --list
.\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish\WifiDirectAgentHelper.exe --peer-name "LTM-Distance-Server"
```

Chạy `--list` trong lúc Linux đang kích hoạt profile P2P, rồi lấy chính xác trường `peerName` được phát hiện để truyền cho `--peer-name`; không giả định tên mẫu `LTM-Distance-Server` luôn đúng.

Chạy trong PowerShell của người dùng đang đăng nhập và chỉ chạy **một** tiến trình helper tại một thời điểm. Lần đầu Windows có thể yêu cầu xác nhận ghép cặp.

Chương trình xuất mỗi trạng thái thành một dòng JSON. `LISTENING` chỉ xuất hiện sau khi WinRT xác nhận publisher đã `Started`; `PUBLISHER_ABORTED` cho biết Windows không thể quảng bá. Khi có yêu cầu, chuỗi chẩn đoán lần lượt là `CONNECTION_REQUESTED`, `PAIRING`, `PAIRED`, `OPENING_DEVICE`, rồi `CONNECTED`. `PAIRING_FAILED` hoặc `UNAVAILABLE` chỉ rõ ranh giới WinRT bị lỗi. Giữ toàn bộ các dòng này khi chẩn đoán kết nối.

Tắt Windows Mobile Hotspot trước khi chạy helper. Microsoft ghi rõ `WiFiDirectAdvertisementPublisher` không được hỗ trợ khi Mobile Hotspot đang hoạt động và publisher có thể dừng.

Sau khi `CONNECTED`, kiểm tra trên Linux bằng `iw dev <p2p-group-interface> station dump`. Chỉ khi MAC P2P của Client xuất hiện trong kết quả này thì Server mới dùng RSSI để ước lượng khoảng cách.
