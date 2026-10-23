# Wi-Fi Direct Agent Helper (Windows)

Tiện ích này dùng API WinRT `Windows.Devices.WiFiDirect` để tìm, ghép cặp và giữ phiên kết nối trực tiếp tới Linux Server. Nó không thay thế kết nối LAN của ứng dụng; LAN vẫn vận chuyển metrics, dxdiag và file.

## Build và chạy

```powershell
dotnet publish .\WifiDirectAgentHelper.csproj -c Release -f net10.0-windows10.0.19041.0 -r win-x64 --self-contained true -p:PublishSingleFile=true
.\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish\WifiDirectAgentHelper.exe --list
.\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish\WifiDirectAgentHelper.exe --peer-name "LTM-Distance-Server"
```

Chạy `--list` trong lúc Linux đang kích hoạt profile P2P, rồi lấy chính xác trường `peerName` được phát hiện để truyền cho `--peer-name`; không giả định tên mẫu `LTM-Distance-Server` luôn đúng.

Chạy trong PowerShell của người dùng đang đăng nhập. Lần đầu Windows có thể yêu cầu xác nhận ghép cặp. Chương trình xuất mỗi trạng thái thành một dòng JSON: `SEARCHING`, `CONNECTED`, `RECONNECTING` hoặc `UNAVAILABLE`.

Sau khi `CONNECTED`, kiểm tra trên Linux bằng `iw dev <p2p-group-interface> station dump`. Chỉ khi MAC P2P của Client xuất hiện trong kết quả này thì Server mới dùng RSSI để ước lượng khoảng cách.
