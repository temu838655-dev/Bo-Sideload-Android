# Bo Sideload

Ứng dụng Android đang được phát triển để hỗ trợ chuẩn bị và quản lý quy trình sideload ứng dụng iOS.

## Trạng thái hiện tại

- Chọn tệp từ bộ nhớ Android bằng trình chọn tệp hệ thống.
- Hiển thị tên và dung lượng tệp.
- Chưa có chức năng ký IPA hoặc cài IPA lên iPhone.
- Chưa có tích hợp hoàn chỉnh giao tiếp với thiết bị Apple.

**Quan trọng:** Tệp có đuôi `.ipa` không đồng nghĩa là IPA hợp lệ. APK Android không thể tự cài IPA lên iPhone. Việc sideload cần quy trình ký phù hợp, Apple ID/chứng thực hợp lệ và kênh giao tiếp được hỗ trợ.

## Tải APK thử nghiệm

1. Mở tab [Actions](https://github.com/temu838655-dev/Bo-Sideload-Android/actions).
2. Chọn workflow chạy thành công mới nhất trên nhánh `main`.
3. Kéo xuống mục **Artifacts**.
4. Tải `Bo-Sideload-debug` và giải nén ZIP để lấy APK.

## GitHub Release

Workflow hiện tải APK lên Actions Artifacts mỗi lần push lên `main`. GitHub Release chỉ được tạo khi push một tag có dạng `v*`.

## Build thủ công

Workflow có thể chạy bằng **Actions → Android APK → Run workflow** nếu cấu hình workflow cho phép.

## Tài liệu kỹ thuật

- [Đánh giá tích hợp Rust iSideload trên Android](docs/ANDROID_PORTING_ASSESSMENT.md): các trở ngại JNI, Android ABI, lưu trữ bí mật, ghép đôi thiết bị và lộ trình kiểm thử.
## Phát triển Android + iOS

- [Kiến trúc hai nền tảng và ma trận tương thích TrollStore](docs/DUAL_PLATFORM_ARCHITECTURE.md)
- [Workspace ứng dụng iOS native](ios/README.md)

Bo Sideload Android là ứng dụng quản lý/điều phối; phần cài đặt vĩnh viễn kiểu TrollStore phải chạy trên iOS tương thích. Hiện workspace iOS mới là tài liệu khởi tạo, chưa phải ứng dụng cài đặt hoàn chỉnh.
