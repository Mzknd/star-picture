package org.example.starpicbackend;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.starpicbackend.manager.PublicPictureCache;
import org.example.starpicbackend.model.dto.picture.PictureQueryRequest;
import org.example.starpicbackend.model.vo.PictureVO;
import org.example.starpicbackend.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicPictureCacheTest {
    PublicPictureCache cache=new PublicPictureCache();
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    ValueOperations<String,String> values=mock(ValueOperations.class);
    Map<String,String> store=new HashMap<>();
    PictureQueryRequest query=new PictureQueryRequest(); AtomicInteger loads=new AtomicInteger();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(cache,"redis",redis); ReflectionTestUtils.setField(cache,"mapper",new ObjectMapper());
        when(redis.opsForValue()).thenReturn(values); when(values.get(anyString())).thenAnswer(c -> store.get(c.getArgument(0)));
        doAnswer(c -> {store.put(c.getArgument(0),c.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        doAnswer(c->{store.put(c.getArgument(0),c.getArgument(1));return null;}).when(values).set(anyString(),anyString());
        when(values.setIfAbsent(anyString(),anyString())).thenAnswer(c->{String key=c.getArgument(0);if(store.containsKey(key)){return false;}store.put(key,c.getArgument(1));return true;});
        query.setNullSpaceId(true);query.setReviewStatus(1);
    }
    Page<PictureVO> load() {loads.incrementAndGet();PictureVO picture=new PictureVO();picture.setId(7L);Page<PictureVO> p=new Page<>(1,10,1);p.setRecords(List.of(picture));return p;}
    @Test void localHitAvoidsDatabaseAndPreservesTypedRecords() {
        cache.get(query,this::load);Page<PictureVO> hit=cache.get(query,this::load);
        assertEquals(1,loads.get());assertEquals(7L,hit.getRecords().get(0).getId());
    }
    @Test void redisHitWorksAfterLocalCacheIsEmpty() {
        cache.get(query,this::load);Object local=ReflectionTestUtils.getField(cache,"local");
        ((com.github.benmanes.caffeine.cache.Cache<?,?>)local).invalidateAll();
        cache.get(query,this::load);assertEquals(1,loads.get());
    }
    @Test void versionChangePreventsOldLocalAndRedisResults() {
        cache.get(query,this::load);cache.invalidateAfterCommit();cache.get(query,this::load);
        assertEquals(2,loads.get());
    }
    @Test void redisFailureFallsBackToDatabase() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        assertEquals(1,cache.get(query,this::load).getTotal());assertEquals(1,loads.get());
    }
    @Test void privateRequestsCannotEnterSharedCache() {
        query.setSpaceId(9L);assertThrows(BusinessException.class,()->cache.get(query,this::load));assertEquals(0,loads.get());
    }
    @Test void databaseFailureIsNotRetriedAsCacheFailure() {
        assertThrows(IllegalStateException.class,()->cache.get(query,()->{loads.incrementAndGet();throw new IllegalStateException("database unavailable");}));
        assertEquals(1,loads.get());
    }
    @Test void redisRecoveryChangesVersionBeforeReusingCachedEntries() {
        cache.get(query,this::load);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("offline"));
        cache.invalidateAfterCommit();cache.get(query,this::load);
        doReturn(values).when(redis).opsForValue();
        cache.get(query,this::load);
        assertEquals(3,loads.get());assertNotNull(store.get(PublicPictureCache.VERSION_KEY));
    }
    @Test void rolledBackTransactionDoesNotInvalidatePublicCache() {
        cache.get(query,this::load);
        String generation=store.get(PublicPictureCache.VERSION_KEY);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateAfterCommit();
            for(var sync:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        cache.get(query,this::load);assertEquals(1,loads.get());assertEquals(generation,store.get(PublicPictureCache.VERSION_KEY));
    }

    @Test void losingGenerationKeyNeverRevivesPreviousLocalEntries() {
        cache.get(query,this::load);String previous=store.get(PublicPictureCache.VERSION_KEY);
        store.remove(PublicPictureCache.VERSION_KEY);cache.get(query,this::load);
        assertEquals(2,loads.get());assertNotEquals(previous,store.get(PublicPictureCache.VERSION_KEY));
    }

}
