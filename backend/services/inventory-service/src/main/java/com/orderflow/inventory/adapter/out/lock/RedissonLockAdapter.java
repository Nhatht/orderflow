package com.orderflow.inventory.adapter.out.lock;

import com.orderflow.inventory.application.port.out.DistributedLockPort;
import com.orderflow.inventory.domain.exception.LockAcquisitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cài đặt {@link DistributedLockPort} bằng Redisson.
 *
 * <p><b>Vì sao Redisson chứ không tự viết {@code SET key value NX PX}?</b>
 * Thuật toán khoá tự chế thường thiếu ba thứ, và mỗi thứ đều gây lỗi thật:
 *
 * <ol>
 *   <li><b>Nhả khoá của người khác.</b> Tiến trình A bị GC pause 12 giây, khoá
 *       hết hạn, B lấy được khoá. A tỉnh dậy và gọi {@code DEL key} — xoá mất
 *       khoá của B. Redisson gắn định danh chủ sở hữu vào khoá và chỉ nhả
 *       khoá của chính mình, bằng Lua script chạy nguyên tử.</li>
 *
 *   <li><b>Không gia hạn được.</b> Đặt lease 10 giây mà xử lý mất 15 giây thì
 *       khoá bay giữa chừng. Redisson có <i>watchdog</i>: nếu không truyền
 *       leaseTime, nó tự gia hạn mỗi 10 giây chừng nào tiến trình còn sống.</li>
 *
 *   <li><b>Không reentrant.</b> Cùng một luồng gọi lồng hai lần sẽ tự khoá
 *       chính mình. {@link RLock} đếm số lần vào như {@code ReentrantLock}.</li>
 * </ol>
 *
 * <p><b>Ở đây ta truyền leaseTime rõ ràng, tức là TẮT watchdog.</b> Có chủ đích:
 * watchdog gia hạn vô thời hạn, nên nếu tiến trình treo mà chưa chết hẳn
 * (deadlock, ổ cứng lỗi), khoá sẽ bị giữ mãi. Một lease hữu hạn bảo đảm khoá
 * luôn được giải phóng. Đổi lại phải chọn leaseTime đủ lớn so với thời gian
 * xử lý thật — và đó chính là lý do phải có lớp optimistic lock phía sau.
 *
 * <p><b>Giới hạn cần biết (phỏng vấn hay hỏi):</b> khoá trên Redis một node
 * KHÔNG an toàn tuyệt đối. Redis master chết trước khi kịp sao chép sang
 * replica thì khoá biến mất và hai tiến trình cùng vào vùng găng. Thuật toán
 * Redlock trên nhiều node độc lập cũng vẫn bị Martin Kleppmann phản biện.
 * Vì vậy khoá ở đây được dùng để GIẢM TRANH CHẤP, còn tính đúng đắn cuối cùng
 * do optimistic lock và CHECK constraint ở database bảo đảm.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonLockAdapter implements DistributedLockPort {

    private final RedissonClient redisson;

    @Override
    public <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redisson.getLock(key);
        boolean acquired = false;

        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            if (!acquired) {
                // KHÔNG chờ vô hạn: dưới tải cao sẽ làm cạn thread pool và
                // kéo sập cả service. Thà trả lỗi để client thử lại.
                log.warn("Failed to acquire lock '{}' within {}", key, waitTime);
                throw new LockAcquisitionException(key);
            }

            log.debug("Lock acquired: {}", key);
            return action.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // khôi phục cờ ngắt, đừng nuốt
            throw new LockAcquisitionException(key, e);

        } finally {
            // isHeldByCurrentThread() là bắt buộc: nếu lease đã hết hạn và
            // khoá sang tay người khác, unlock() sẽ ném
            // IllegalMonitorStateException — che mất exception gốc đang bay lên.
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", key);
            }
        }
    }

    /**
     * Khoá nhiều key cùng lúc bằng {@code RedissonMultiLock}: lấy được TẤT CẢ
     * thì chạy, thiếu một cái thì nhả hết những cái đã lấy rồi thử lại, hết
     * {@code waitTime} thì bỏ cuộc.
     *
     * <p><b>Sắp xếp key trước khi khoá</b> — đây là kỹ thuật kinh điển chống
     * deadlock (lock ordering). Hai đơn cùng chứa kẹo dừa và bánh tráng, nếu
     * mỗi đơn khoá theo thứ tự dòng hàng của mình thì có thể mỗi bên cầm một
     * khoá và chờ khoá của bên kia. MultiLock không treo vĩnh viễn nhờ có
     * {@code waitTime}, nhưng hai bên sẽ cùng thất bại rồi cùng thử lại
     * (livelock). Khoá theo thứ tự chung thì bên đến sau luôn chờ ở khoá ĐẦU
     * TIÊN, không bao giờ cầm dở dang.
     */
    @Override
    public <T> T executeWithLocks(Collection<String> keys, Duration waitTime, Duration leaseTime,
                                  Supplier<T> action) {
        List<String> ordered = keys.stream().distinct().sorted().toList();
        if (ordered.size() == 1) {
            return executeWithLock(ordered.getFirst(), waitTime, leaseTime, action);
        }

        List<RLock> locks = ordered.stream().map(redisson::getLock).toList();
        RLock multiLock = redisson.getMultiLock(locks.toArray(RLock[]::new));
        boolean acquired = false;

        try {
            acquired = multiLock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            if (!acquired) {
                log.warn("Failed to acquire {} locks {} within {}", ordered.size(), ordered, waitTime);
                throw new LockAcquisitionException(String.join(",", ordered));
            }

            log.debug("Locks acquired: {}", ordered);
            return action.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(String.join(",", ordered), e);

        } finally {
            // Nhả TỪNG khoá con, không gọi multiLock.unlock(): khoá nào đã hết
            // lease và sang tay người khác thì multiLock.unlock() ném exception,
            // che mất exception gốc. Cùng lý do với isHeldByCurrentThread() ở trên.
            if (acquired) {
                for (RLock lock : locks) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
                log.debug("Locks released: {}", ordered);
            }
        }
    }
}
