# AutoClicker

Ứng dụng tự động bấm màn hình bằng Dịch vụ trợ năng (AccessibilityService), có bảng điều khiển nổi.

## Tính năng

- Tạo **nhiều điểm** bấm; kéo thả để đặt vị trí, chạm để chọn, giữ lâu để mở cài đặt điểm.
- Mỗi điểm có thông số riêng: **khoảng thời gian giữa 2 lần bấm (ms)**, thời gian giữ, số lần bấm (0 = vô hạn).
- **Cài đặt 1 điểm** ("Lưu điểm này") hoặc **toàn bộ điểm** ("Áp dụng TẤT CẢ").
- **Chạy 1 điểm đã chọn** hoặc **chạy tất cả điểm** (nút chế độ trên bảng điều khiển).
- **5 bản lưu** (Ô 1 → Ô 5): lưu / tải / xoá toàn bộ cấu hình (điểm, thời gian, chế độ).
- **Cập nhật OTA qua GitHub Releases**: kiểm tra bản mới, tải APK, kiểm tra SHA-256, mở trình cài đặt.

## Chạy thử

1. Mở app → "1. Bật dịch vụ trợ năng" → bật AutoClicker.
   - Android 13+ nếu cài APK ngoài Play Store: Cài đặt > Ứng dụng > AutoClicker > ⋮ > **Cho phép cài đặt bị hạn chế**, rồi mới bật được trợ năng.
2. "2. Hiện bảng điều khiển nổi" → dùng các nút: ＋ Thêm điểm, ⚙ Cài đặt điểm, chế độ chạy, 💾 Bản lưu, ▶ Chạy.
3. Khi đang chạy, các điểm nổi tự "xuyên thấu" để cú bấm đi xuống ứng dụng bên dưới. Bấm ■ Dừng để dừng.

## Giới hạn cần biết

- Cần Android 8.0+ (API 26).
- Một số ứng dụng (ngân hàng, game có chống hack) có thể chặn thao tác từ dịch vụ trợ năng.
- Thời gian bấm có sai số vài chục ms do hệ thống; khoảng cách tối thiểu là 20 ms. Các điểm được bấm tuần tự (không bấm đồng thời 2 điểm).
- Mã nguồn được viết nhưng **chưa được biên dịch/chạy thử trên thiết bị thật** trong môi trường tạo ra nó; nếu Android Studio báo lỗi nhỏ khi build, hãy gửi lại thông báo lỗi để sửa.

## Cấu trúc

| File | Vai trò |
|---|---|
| `ClickService.kt` | Dịch vụ trợ năng: bảng điều khiển nổi, điểm kéo thả, bộ lập lịch bấm, hộp thoại cài đặt/bản lưu |
| `Models.kt` | `ClickPoint`, `Profile` (+ JSON) |
| `Storage.kt` | Lưu cấu hình hiện tại và 5 slot (SharedPreferences) |
| `Updater.kt` | Kiểm tra/tải/cài bản cập nhật từ GitHub Releases |
| `MainActivity.kt` | Màn hình chính: bật dịch vụ, hiện/ẩn bảng nổi, cập nhật |
