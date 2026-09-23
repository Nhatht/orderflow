package com.orderflow.inventory.application.port.out;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * CỔNG RA cho distributed lock.
 *
 * <p>Vì sao khai interface riêng thay vì gọi thẳng Redisson: tầng application
 * chỉ cần khái niệm "chạy đoạn này sao cho không ai chạy song song". Nó không
 * cần biết đó là Redis, ZooKeeper hay database advisory lock. Đổi hạ tầng
 * khoá chỉ cần viết adapter mới.
 *
 * <p>Cũng nhờ đó, unit test thay bằng một implementation chạy thẳng không khoá,
 * không cần dựng Redis.
 */
public interface DistributedLockPort {

    /**
     * Chạy {@code action} trong vùng độc quyền theo {@code key}.
     *
     * @param waitTime  chờ tối đa bao lâu để lấy khoá. KHÔNG được để vô hạn:
     *                  dưới tải cao, chờ vô hạn làm cạn thread pool và kéo sập
     *                  cả service. Hết thời gian thì ném
     *                  {@code LockAcquisitionException} để client thử lại.
     * @param leaseTime giữ khoá tối đa bao lâu rồi tự nhả. Đây là chống chết
     *                  cứng: nếu tiến trình giữ khoá bị kill, khoá vẫn được
     *                  giải phóng thay vì treo vĩnh viễn — điều mà coordinator
     *                  của 2PC không làm được.
     */
    <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /**
     * Như {@link #executeWithLock}, nhưng giữ NHIỀU khoá cùng lúc — tất cả
     * hoặc không cái nào.
     *
     * <p>Dùng khi một thao tác đụng nhiều tài nguyên, ví dụ giữ hàng cho cả
     * đơn có nhiều sản phẩm. Cài đặt phải lấy khoá theo một THỨ TỰ CỐ ĐỊNH
     * để tránh deadlock: luồng A khoá kẹo-dừa rồi chờ bánh-tráng, luồng B khoá
     * bánh-tráng rồi chờ kẹo-dừa — cả hai chờ nhau mãi.
     */
    <T> T executeWithLocks(Collection<String> keys, Duration waitTime, Duration leaseTime, Supplier<T> action);
}
