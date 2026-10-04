package org.example.starpicbackend.manager;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.exception.*;
import org.example.starpicbackend.model.dto.picture.PictureQueryRequest;
import org.example.starpicbackend.model.vo.PictureVO;
import org.example.starpicbackend.utils.TransactionActions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** 仅缓存公共已审核图片；版本号用于事务提交后的跨进程失效。 */
@Slf4j
@Component
public class PublicPictureCache {
    public static final String VERSION_KEY="starpicture:public-list:version";
    @Resource private StringRedisTemplate redis;
    @Resource private ObjectMapper mapper;
    private final Cache<String,String> local=Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(2)).recordStats().build();
    private volatile boolean needsRecovery;

    public Page<PictureVO> get(PictureQueryRequest query,Supplier<Page<PictureVO>> loader) {
        ThrowUtils.throwIf(query==null,ErrorCode.PARAMS_ERROR,"查询不能为空");
        ThrowUtils.throwIf(query.getSpaceId()!=null || !query.isNullSpaceId()
                || !Integer.valueOf(1).equals(query.getReviewStatus()),ErrorCode.NO_AUTH_ERROR,"缓存仅支持公共已审核列表");
        String key;
        try {
            if(needsRecovery) {redis.opsForValue().set(VERSION_KEY, UUID.randomUUID().toString()); needsRecovery=false;}
            String version=redis.opsForValue().get(VERSION_KEY);
            if(version==null) {
                String candidate=UUID.randomUUID().toString();
                if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(VERSION_KEY,candidate))) {version=candidate;}
                else {version=redis.opsForValue().get(VERSION_KEY);}
                if(version==null) {throw new IllegalStateException("缓存版本不可用");}
            }
            key="starpicture:public-list:"+version+":"
                    + DigestUtils.md5DigestAsHex(mapper.writeValueAsBytes(query));
        } catch(Exception e) {local.invalidateAll(); needsRecovery=true; return loader.get();}
        try {
            String json=local.get(key,k -> load(k,loader));
            Snapshot snapshot=mapper.readValue(json,Snapshot.class);
            Page<PictureVO> page=new Page<>(snapshot.current,snapshot.size,snapshot.total);
            page.setRecords(snapshot.records); return page;
        } catch(DatabaseLoadException e) {
            throw (RuntimeException)e.getCause();
        } catch(Exception e) {
            local.invalidate(key);
            needsRecovery=true;
            log.warn("公共列表缓存不可用，回源数据库");
            return loader.get();
        }
    }
    private String load(String key,Supplier<Page<PictureVO>> loader) {
        try {String hit=redis.opsForValue().get(key); if(hit!=null){return hit;}}
        catch(Exception e) {needsRecovery=true;}
        Page<PictureVO> page;
        try {page=loader.get();}
        catch(RuntimeException e) {throw new DatabaseLoadException(e);}
        Snapshot snapshot=new Snapshot(); snapshot.current=page.getCurrent(); snapshot.size=page.getSize();
        snapshot.total=page.getTotal(); snapshot.records=page.getRecords();
        try {
            String json=mapper.writeValueAsString(snapshot);
            try {redis.opsForValue().set(key,json,Duration.ofSeconds(300+ThreadLocalRandom.current().nextInt(300)));}
            catch(Exception e) {needsRecovery=true;}
            return json;
        } catch(Exception e) {throw new IllegalStateException("缓存序列化失败",e);}
    }
    public void invalidateAfterCommit() {
        TransactionActions.afterCommit(() -> {
            local.invalidateAll();
            try {redis.opsForValue().set(VERSION_KEY, UUID.randomUUID().toString()); needsRecovery=false;}
            catch(Exception e) {needsRecovery=true; log.warn("Redis 缓存失效失败，恢复后先切换版本");}
        });
    }
    private static class DatabaseLoadException extends RuntimeException {
        DatabaseLoadException(RuntimeException cause) {super(cause);}
    }
    @Data public static class Snapshot {
        private long current,size,total;
        private List<PictureVO> records;
    }
}
