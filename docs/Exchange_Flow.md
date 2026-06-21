# Các Actor chính:
- **Trader** (người dùng)
- **Sàn giao dịch (Binance)** — bao gồm các hệ thống con bên trong
- **Blockchain Network** (ví dụ: Bitcoin, Ethereum)
- **Ngân hàng / Payment Gateway** (nạp fiat)
- **KYC Provider** (xác minh danh tính)

# Usecases
## 1. Đăng ký & Xác minh (Onboarding)
- Trader tạo tài khoản bằng email/số điện thoại
- Kích hoạt 2FA (Google Authenticator / SMS)
- Hoàn thành KYC: upload CMND/passport + selfie → gửi tới KYC Provider → kết quả verified/rejected
- Tài khoản được kích hoạt, các ví (spot wallet) được tạo tự động cho từng loại asset (BTC, USDT, ETH, ...)

## 2. Nạp tiền (Deposit)
- **Nạp crypto:** Trader lấy địa chỉ ví deposit từ sàn → gửi crypto từ ví ngoài → giao dịch broadcast lên Blockchain → sàn lắng nghe on-chain confirmations (ví dụ 3 confirmations cho BTC) → khi đủ confirmations, số dư ví spot được cộng
- **Nạp fiat:** Trader chọn phương thức (chuyển khoản ngân hàng, thẻ tín dụng, P2P) → tiền đi qua Payment Gateway → sàn nhận xác nhận → cộng USDT/fiat vào ví

## 4. Phân tích thị trường (Market Analysis)
- Trader mở giao diện trading, chọn cặp giao dịch (ví dụ BTC/USDT)
- Xem biểu đồ nến (candlestick chart) — dữ liệu OHLCV realtime
- Xem Order Book: danh sách lệnh mua (bid) và bán (ask) đang chờ khớp, hiển thị giá và khối lượng
- Xem Trade History: các giao dịch vừa khớp gần đây
- Xem ticker: giá hiện tại, biến động 24h, volume 24h
- Sử dụng các indicator kỹ thuật (MA, RSI, MACD,...) trên chart

## 4. Đặt lệnh (Order Placement)
- Trader chọn loại lệnh:
  - **Market Order:** mua/bán ngay tại giá thị trường hiện tại
  - **Limit Order:** đặt giá mong muốn, lệnh sẽ nằm chờ trên order book cho đến khi có người khớp
  - *(Nâng cao: Stop-Limit, OCO, Trailing Stop)*
- Trader nhập: cặp giao dịch, hướng (BUY/SELL), số lượng, giá (nếu limit)
- Hệ thống validate: kiểm tra số dư đủ không, tick size, step size, min notional
- **Freeze balance:** Nếu BUY BTC/USDT, sàn đóng băng (freeze) số USDT tương ứng trong ví để đảm bảo không bị double-spend. Số tiền freeze = giá × số lượng (+ buffer cho market order)
- Lệnh được ghi nhận → trạng thái: NEW

## 4. Khớp lệnh (Order Matching — Matching Engine)
- Lệnh được đẩy vào **Matching Engine** — trái tim của sàn
- **Market Order:** engine lấy các lệnh đối ứng tốt nhất từ order book và khớp ngay. Ví dụ: BUY market 0.5 BTC → khớp với các lệnh SELL giá thấp nhất, lần lượt từng mức giá (walk-the-book) cho đến khi đủ 0.5 BTC
- **Limit Order:** lệnh được đặt vào order book tại mức giá chỉ định. Nằm chờ cho đến khi có lệnh đối ứng ở cùng mức giá hoặc tốt hơn → khớp
- Khớp lệnh tuân theo nguyên tắc **Price-Time Priority (FIFO):** cùng mức giá → ai đặt trước khớp trước
- Kết quả khớp: tạo ra **Trade** — ghi nhận giá khớp, khối lượng, phí, thời gian
- Trạng thái lệnh cập nhật: NEW → PARTIALLY_FILLED → FILLED (hoặc CANCELLED)

## 4. Xử lý sau khớp lệnh (Post-Trade Settlement)
- **Cập nhật ví ngay lập tức (atomic):**
  - Người mua: trừ USDT (giải phóng freeze, trừ thực tế) + cộng BTC (trừ phí)
  - Người bán: trừ BTC + cộng USDT (trừ phí)
- **Tính phí giao dịch:**
  - Maker fee (người đặt limit order tạo thanh khoản): thường thấp hơn (ví dụ 0.1%)
  - Taker fee (người lấy thanh khoản — market order hoặc limit order khớp ngay): cao hơn (ví dụ 0.1%)
  - Phí có thể giảm theo VIP tier hoặc khi sử dụng BNB trả phí
- **Giải phóng freeze dư:** Nếu market buy chỉ khớp hết ở giá thấp hơn dự kiến → phần USDT freeze thừa được trả lại available
- **Ghi audit log:** Mỗi thay đổi số dư tạo một bản ghi transaction (TRADE_DEBIT, TRADE_CREDIT, FEE_DEBIT, ORDER_UNFREEZE)

## 4. Theo dõi danh mục (Portfolio Management)
- Trader xem tổng quan ví: total balance, available balance, frozen (in-order) balance cho từng asset
- Xem lịch sử lệnh: danh sách tất cả lệnh đã đặt và trạng thái
- Xem lịch sử giao dịch: chi tiết từng trade đã khớp (giá, lượng, phí, thời gian)
- Ước tính giá trị portfolio theo USDT dựa trên giá thị trường hiện tại
- Hủy lệnh limit đang chờ (nếu chưa khớp hết) → số tiền freeze được giải phóng

## 4. Rút tiền (Withdrawal)
- **Rút crypto:** Trader nhập địa chỉ ví đích + số lượng → sàn xác minh (2FA, email confirm, whitelist check) → sàn tạo transaction trên Blockchain → chờ confirmations → hoàn tất
- **Rút fiat:** Trader yêu cầu rút → sàn xử lý qua Payment Gateway → chuyển khoản ngân hàng → hoàn tất (thường mất 1-3 ngày)
