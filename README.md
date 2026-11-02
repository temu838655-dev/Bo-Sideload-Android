# Bo Sideload (Android)

Bo Sideload là ứng dụng **Android APK** để quản lý, kiểm tra cấu trúc và chuẩn bị tệp IPA trên điện thoại Android.

## Phạm vi dự án

Dự án này chỉ phát triển ứng dụng Android. Không xây dựng ứng dụng iOS riêng và không đưa mã nguồn TrollStore vào APK Android.

## Tính năng hiện có

- Chọn tệp bằng trình chọn tài liệu của Android.
- Hiển thị tên và dung lượng tệp.
- Đọc cấu trúc ZIP của IPA.
- Tìm `Payload/<ứng dụng>.app/Info.plist`.
- Đọc metadata cơ bản từ plist XML và binary: tên ứng dụng, Bundle ID, phiên bản, mã bản dựng (nếu đọc được).
- Giới hạn dữ liệu plist được phân tích ở 1 MB.
- Từ chối đường dẫn ZIP không an toàn.
- Giới hạn số lượng mục và tổng dữ liệu giải nén để giảm nguy cơ tệp ZIP độc hại.

## Giới hạn hiện tại

- Chỉ đọc metadata; không xác minh chữ ký IPA, chứng chỉ hoặc khả năng cài đặt.
- Chưa ký IPA.
- Chưa cài IPA lên iPhone.
- Chưa có kết nối/chuyển tệp sang iPhone.

**Lưu ý:** Android không thể chạy TrollStore để cài IPA trực tiếp lên iPhone. Mọi tính năng cài đặt trong tương lai phải dựa trên phương thức tương thích thực tế và được mô tả chính xác.

## Tải APK thử nghiệm

1. Mở [GitHub Actions](https://github.com/temu838655-dev/Bo-Sideload-Android/actions).
2. Chọn lần chạy workflow thành công mới nhất trên nhánh `main`.
3. Kéo xuống mục **Artifacts**.
4. Tải `Bo-Sideload-debug` và giải nén để lấy APK.

## GitHub Release

Workflow tải APK lên Actions Artifacts sau mỗi lần push lên `main`. GitHub Release chỉ được tạo khi push tag có dạng `v*`.

## Build

Có thể chạy workflow thủ công tại **Actions → Android APK → Run workflow**.
