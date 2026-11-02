# Bo Sideload — kiến trúc hai nền tảng

## Mục tiêu

Bo Sideload gồm hai ứng dụng phối hợp nhưng có nhiệm vụ và mã nguồn riêng:

- **Bo Sideload Android (repo hiện tại):** quản lý thư viện IPA, kiểm tra cấu trúc/metadata, lưu hồ sơ thiết bị và làm giao diện điều khiển cho các quy trình được hỗ trợ. Android không thể thực thi cơ chế cài đặt nội bộ của iOS chỉ bằng Kotlin.
- **Bo Sideload iOS (mục tiêu native):** ứng dụng iOS/iPadOS, phát triển từ mã nguồn TrollStore chính thức khi thiết bị và phiên bản hệ điều hành đáp ứng điều kiện tương thích. Đây mới là nơi có thể tích hợp các thành phần cài đặt/permasigning đặc thù iOS.

## Nguồn upstream và giới hạn tương thích

- Upstream: https://github.com/opa334/TrollStore
- README upstream mô tả TrollStore dựa trên lỗi xác minh chữ ký AMFI/CoreTrust, không phải kỹ thuật cài IPA phổ quát.
- Danh sách phiên bản được upstream công bố tại thời điểm khảo sát: iOS 14.0 beta 2 đến 16.6.1, 16.7 RC (20H18) và 17.0. Không được quảng cáo hỗ trợ iOS 17.0.1 trở lên nếu upstream không công bố phương thức tương thích mới.
- Trang Releases chính thức: https://github.com/opa334/TrollStore/releases

Không được hứa hẹn cài vĩnh viễn trên mọi iPhone. Trước mọi thao tác phải kiểm tra chính xác phiên bản iOS, model/kiến trúc, trạng thái thiết bị và phương thức cài đặt khả dụng. Không tự động chạy exploit hoặc thay đổi hệ thống khi chưa có xác nhận rõ ràng của người dùng.

## Phân chia module

### Android

1. IPA library: nhập, đổi tên hiển thị, dung lượng, SHA-256 và metadata.
2. IPA inspector: xác thực ZIP, đường dẫn an toàn, Info.plist, bundle identifier, version và biểu tượng.
3. Device workspace: hồ sơ iPhone, phiên bản iOS do người dùng cung cấp/được đọc qua giao thức được hỗ trợ.
4. Workflow router: chỉ hiển thị phương thức cài đặt tương thích; không giả lập TrollStore trên Android.
5. Transfer: chuyển tệp qua phương thức được người dùng chấp thuận; không lưu Apple ID/password dạng văn bản.

### iOS

1. Native TrollStore UI/installation components từ upstream.
2. Compatibility gate trước khi hiện phương thức cài.
3. Persistence/helper handling theo đúng upstream và chỉ khi thiết bị đủ điều kiện.
4. IPA import, signing/install pipeline, app registration và recovery.
5. Audit log, xác nhận trước thao tác rủi ro và xử lý lỗi có thể khôi phục.

## Quy tắc tái sử dụng mã nguồn và giấy phép

Repository TrollStore có giấy phép khác nhau theo phạm vi tệp: phần lớn mã nguồn được ghi MIT, trong khi một số tệp (ví dụ RootHelper/uicache.m) có BSD-4-Clause. Trước khi sao chép từng tệp phải kiểm tra header và LICENSE tương ứng, giữ nguyên copyright/license notices, ghi rõ thay đổi và không gắn nhãn Bo Sideload là dự án chính thức của TrollStore. Không được giả định toàn bộ repository có một giấy phép duy nhất.

## Lộ trình và tiêu chí hoàn thành

- [x] Tách rõ trách nhiệm Android và iOS, ghi nhận giới hạn tương thích.
- [x] Xác nhận workflow Android hiện tại có thể build APK ở commit trước đó.
- [ ] Hoàn tất kiểm tra IPA và unit tests trên Android (ZIP traversal, ZIP bomb, Info.plist malformed).
- [ ] Tạo repository iOS riêng và import upstream theo đúng license.
- [ ] Thiết lập build iOS trên macOS runner/Xcode; không dùng Android Gradle để build iOS.
- [ ] Thêm compatibility gate và test matrix cho các phiên bản iOS được upstream hỗ trợ.
- [ ] Chạy kiểm thử trên thiết bị thật phù hợp; không tuyên bố cài đặt thành công chỉ dựa vào build.
- [ ] Xây cầu nối Android ↔ iOS an toàn, có xác thực và xác nhận thao tác.

## Trạng thái trung thực

APK Android hiện tại chỉ là nền tảng quản lý/kiểm tra IPA. Nó chưa ký IPA, chưa cài IPA lên iPhone và chưa chứa exploit/CoreTrust implementation. Chưa có ứng dụng iOS Bo Sideload hoặc bản build iOS trong repository này.
