package com.orderflow.inventory.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.application.dto.StockView;
import com.orderflow.inventory.application.port.out.StockCachePort;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache tồn kho trên Redis — dùng lại {@link RedissonClient} đã có cho khoá
 * phân tán, không thêm client Redis thứ hai.
 *
 * <p><b>Vì sao vẫn có TTL dù đã xoá theo event:</b> cache-aside có một cuộc đua
 * kinh điển mà xoá theo event không bịt được:
 * <pre>
 *   t1  A đọc: cache miss → đọc DB được available=5
 *   t2  B giữ hàng: DB available=4, event stock.changed → XOÁ cache (lúc này cache đang rỗng)
 *   t3  A ghi cache available=5                ← giá trị CŨ nằm lại trong cache
 * </pre>
 * TTL là giới hạn trên cho độ cũ trong trường hợp đó: tối đa {@code stock-ttl}.
 * Cách bịt hơn nữa (xoá hai lần có trễ, hoặc version trong key) chưa đáng với
 * dữ liệu chỉ để hiển thị.
 *
 * <p>Lưu JSON dạng chuỗi (không dùng codec Java serialization của Redisson): đọc
 * được bằng {@code redis-cli GET}, và đổi class không làm hỏng dữ liệu cũ.
 */
@Slf4j
@Component
public class RedisStockCacheAdapter implements StockCachePort {

    static final String KEY_PREFIX = "orderflow:cache:stock:";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisStockCacheAdapter(RedissonClient redisson, ObjectMapper objectMapper,
                                  @Value("${orderflow.inventory.cache.stock-ttl:PT60S}") Duration ttl) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Optional<StockView> get(UUID productId) {
        try {
            String json = bucket(productId).get();
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, StockView.class));
        } catch (RuntimeException | JsonProcessingException e) {
            // Redis sập không được làm sập trang sản phẩm: coi như miss, đọc DB.
            log.warn("Stock cache read failed for product={}, falling back to database: {}", productId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(StockView stock) {
        try {
            bucket(stock.productId()).set(objectMapper.writeValueAsString(stock), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Stock cache write failed for product={}: {}", stock.productId(), e.toString());
        }
    }

    @Override
    public void evict(UUID productId) {
        bucket(productId).delete();   // lỗi bay ra → listener retry → DLT
    }

    private RBucket<String> bucket(UUID productId) {
        return redisson.getBucket(KEY_PREFIX + productId, StringCodec.INSTANCE);
    }
}
