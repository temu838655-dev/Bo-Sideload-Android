# Bo Sideload iOS — workspace khởi tạo

Đây là workspace ghi nhận kế hoạch cho ứng dụng iOS native. Mã nguồn TrollStore phải được tích hợp từ upstream chính thức: https://github.com/opa334/TrollStore

## Nguyên tắc

- Không dùng Kotlin/Android APK làm bộ cài TrollStore.
- Chỉ bật chức năng cài vĩnh viễn khi phiên bản/model iOS nằm trong ma trận upstream đã xác minh.
- Không tuyên bố hỗ trợ iOS mới hơn chỉ vì ứng dụng biên dịch được.
- Giữ nguyên license/copyright header của từng tệp upstream; kiểm tra MIT và BSD-4-Clause theo từng đường dẫn.
- Build native bằng Xcode trên macOS; kiểm thử cài đặt trên thiết bị thật tương thích.

## Chưa triển khai

Workspace này chưa chứa project Xcode, exploit, helper hay binary TrollStore. Các thành phần đó chỉ được thêm sau khi audit mã nguồn, giấy phép và ma trận thiết bị/OS.
