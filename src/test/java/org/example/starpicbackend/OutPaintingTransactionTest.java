package org.example.starpicbackend;

import com.qcloud.cos.COSClient;
import org.example.starpicbackend.api.outpainting.*;
import org.example.starpicbackend.config.OutPaintingProperties;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.manager.*;
import org.example.starpicbackend.manager.upload.*;
import org.example.starpicbackend.mapper.OutPaintingTaskMapper;
import org.example.starpicbackend.model.dto.file.UploadPictureResult;
import org.example.starpicbackend.model.dto.outpainting.*;
import org.example.starpicbackend.model.dto.picture.PictureUploadRequest;
import org.example.starpicbackend.model.dto.space.SpaceAddRequest;
import org.example.starpicbackend.model.entity.*;
import org.example.starpicbackend.model.vo.*;
import org.example.starpicbackend.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "AI_OUT_PAINTING_ENABLED=true",
        "spring.sql.init.schema-locations=classpath:schema-test.sql,classpath:schema-out-painting-test.sql"
})
@ActiveProfiles("test")
class OutPaintingTransactionTest {
    @Autowired OutPaintingService outPainting;
    @Autowired OutPaintingTaskMapper tasks;
    @Autowired OutPaintingProperties properties;
    @Autowired PictureService pictures;
    @Autowired SpaceService spaces;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockBean OutPaintingProvider provider;
    @MockBean COSClient cosClient;
    @MockBean CosManager cos;
    @MockBean PublicPictureCache cache;
    @MockBean FilePictureUpload files;
    @MockBean UrlPictureUpload urls;
    @MockBean PictureFileCleanup cleanup;
    User owner;
    AtomicInteger providerSequence;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM out_painting_task"); jdbc.update("DELETE FROM picture");
        jdbc.update("DELETE FROM space"); jdbc.update("DELETE FROM user");
        properties.setEnabled(true); properties.setDailyLimit(5); properties.setActiveLimit(2);
        owner = new User(); owner.setId(101L); owner.setUserAccount("ai-owner"); owner.setUserPassword("test-hash");
        owner.setUserRole("user"); users.save(owner);
        properties.setAllowedUserIds(owner.getId().toString());
        providerSequence = new AtomicInteger();
        when(provider.create(anyString(),any())).thenAnswer(call ->
                new ProviderTask("provider-"+providerSequence.incrementAndGet(),"PENDING",null,null));
        when(provider.query(anyString())).thenAnswer(call ->
                new ProviderTask(call.getArgument(0),"SUCCEEDED","https://example.invalid/generated.png",null));
        when(files.uploadPicture(any(),anyString())).thenReturn(uploaded("source"));
        when(urls.uploadPicture(any(),anyString())).thenReturn(uploaded("generated"));
        when(cos.signedUrl(anyString(),anyInt())).thenAnswer(call -> call.getArgument(0));
    }
    UploadPictureResult uploaded(String name) {
        UploadPictureResult result = new UploadPictureResult(); result.setPicName(name); result.setPicSize(60L);
        result.setPicWidth(1024); result.setPicHeight(1024); result.setPicFormat("webp");
        result.setUrl("https://example.invalid/"+name+".webp"); return result;
    }
    Long source(Long spaceId) {
        PictureUploadRequest request = new PictureUploadRequest(); request.setSpaceId(spaceId);
        return pictures.uploadPicture(new Object(),request,owner).getId();
    }
    Space space(long count,long bytes) {
        long id = spaces.addSpace(new SpaceAddRequest(),owner);
        Space space = spaces.getById(id); space.setMaxCount(count); space.setMaxSize(bytes); spaces.updateById(space); return space;
    }
    CreateOutPaintingTaskRequest createRequest(Long sourceId) {
        CreateOutPaintingTaskRequest request = new CreateOutPaintingTaskRequest(); request.setPictureId(sourceId);
        OutPaintingParameters p = new OutPaintingParameters(); p.setXScale(1.5); p.setYScale(1.5); request.setParameters(p); return request;
    }
    SaveOutPaintingTaskRequest saveRequest(Long taskId) {
        SaveOutPaintingTaskRequest request = new SaveOutPaintingTaskRequest(); request.setTaskId(taskId); return request;
    }

    @Test void anotherUserCannotCreateFromPrivateSourceOrQueryAndSaveTask() {
        Space space = space(5,1000); Long sourceId = source(space.getId());
        User other = new User(); other.setId(102L); other.setUserAccount("other"); other.setUserPassword("test-hash");
        other.setUserRole("user"); users.save(other);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(),assertThrows(BusinessException.class,
                () -> outPainting.createTask(createRequest(sourceId),other)).getCode());
        OutPaintingTaskVO task = outPainting.createTask(createRequest(sourceId),owner);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(),assertThrows(BusinessException.class,
                () -> outPainting.getTask(task.getId(),other)).getCode());
        assertThrows(BusinessException.class, () -> outPainting.saveResult(saveRequest(task.getId()),other));
        verify(provider,never()).query(anyString());
    }

    @Test void repeatedAndConcurrentSavesUploadOnceAndUseSpaceQuotaOnce() throws Exception {
        Space space = space(5,1000); Long sourceId = source(space.getId());
        Long taskId = outPainting.createTask(createRequest(sourceId),owner).getId();
        assertEquals("SUCCEEDED",outPainting.getTask(taskId,owner).getStatus());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<PictureVO> save = () -> { ready.countDown(); assertTrue(start.await(5,TimeUnit.SECONDS));
                return outPainting.saveResult(saveRequest(taskId),owner); };
            Future<PictureVO> a = pool.submit(save), b = pool.submit(save);
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            Long aId = a.get(10,TimeUnit.SECONDS).getId(), bId = b.get(10,TimeUnit.SECONDS).getId();
            assertEquals(aId,bId); assertEquals(aId,outPainting.saveResult(saveRequest(taskId),owner).getId());
            verify(urls,times(1)).uploadPicture(any(),anyString());
            assertEquals(2,pictures.count()); assertEquals(2,spaces.getById(space.getId()).getTotalCount());
            assertEquals(120,spaces.getById(space.getId()).getTotalSize());
            assertEquals(aId,tasks.selectById(taskId).getSavedPictureId());
            assertNull(tasks.selectById(taskId).getOutputImageUrl());
        } finally { pool.shutdownNow(); }
    }

    @Test void exhaustedSpaceKeepsSuccessfulTaskUnsavedAndCompensatesUpload() {
        Space space = space(1,1000); Long sourceId = source(space.getId());
        Long taskId = outPainting.createTask(createRequest(sourceId),owner).getId();
        assertThrows(BusinessException.class, () -> outPainting.saveResult(saveRequest(taskId),owner));
        assertEquals(1,pictures.count()); assertEquals(1,spaces.getById(space.getId()).getTotalCount());
        assertNull(tasks.selectById(taskId).getSavedPictureId());
        assertEquals("SUCCEEDED",tasks.selectById(taskId).getStatus());
    }

    @Test void outerRollbackRevertsTaskBindingPictureAndQuotaAndCleansCos() {
        Space space = space(5,1000); Long sourceId = source(space.getId());
        Long taskId = outPainting.createTask(createRequest(sourceId),owner).getId();
        outPainting.getTask(taskId,owner);
        assertThrows(IllegalStateException.class, () -> transactions.execute(tx -> {
            outPainting.saveResult(saveRequest(taskId),owner); throw new IllegalStateException("rollback");
        }));
        assertNull(tasks.selectById(taskId).getSavedPictureId()); assertEquals(1,pictures.count());
        assertEquals(1,spaces.getById(space.getId()).getTotalCount()); verify(cleanup).cleanup(any(Picture.class));
    }

    @Test void failedSubmissionReturnsInspectableLocalTaskAndConsumesDailyBudget() {
        properties.setDailyLimit(1); Long sourceId = source(null);
        when(provider.create(anyString(),any())).thenThrow(new OutPaintingProviderException("PROVIDER_HTTP_ERROR"));
        OutPaintingTaskVO failed = outPainting.createTask(createRequest(sourceId),owner);
        assertEquals("FAILED",failed.getStatus()); assertEquals("PROVIDER_HTTP_ERROR",failed.getErrorCode());
        assertThrows(BusinessException.class, () -> outPainting.createTask(createRequest(sourceId),owner));
        verify(provider,times(1)).create(anyString(),any());
    }

    @Test void concurrentCreatesCannotExceedActiveAdmissionLimit() throws Exception {
        properties.setActiveLimit(1); Long sourceId = source(null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> create = () -> { try { outPainting.createTask(createRequest(sourceId),owner); return true; }
                catch(BusinessException expected) { return false; } };
            Future<Boolean> a = pool.submit(create), b = pool.submit(create);
            assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM out_painting_task",Integer.class));
            verify(provider,times(1)).create(anyString(),any());
        } finally { pool.shutdownNow(); }
    }

    @Test void timeoutDoesNotQueryProviderAndSavedTaskRemainsIdempotentAfterExpiry() {
        Long sourceId = source(null);
        Long expired = outPainting.createTask(createRequest(sourceId),owner).getId();
        jdbc.update("UPDATE out_painting_task SET expiresAt = ? WHERE id = ?",new Date(System.currentTimeMillis()-1000),expired);
        assertEquals("EXPIRED",outPainting.getTask(expired,owner).getStatus());
        verify(provider,never()).query(anyString());
        assertThrows(BusinessException.class, () -> outPainting.saveResult(saveRequest(expired),owner));
        Long savedTask = outPainting.createTask(createRequest(sourceId),owner).getId();
        Long savedPicture = outPainting.saveResult(saveRequest(savedTask),owner).getId();
        jdbc.update("UPDATE out_painting_task SET expiresAt = ? WHERE id = ?",new Date(System.currentTimeMillis()-1000),savedTask);
        assertEquals(savedPicture,outPainting.saveResult(saveRequest(savedTask),owner).getId());
        assertEquals("SUCCEEDED",tasks.selectById(savedTask).getStatus());
    }

    @Test void olderPollCannotOverwriteSuccessAndPollingIsThrottled() {
        Long taskId = outPainting.createTask(createRequest(source(null)),owner).getId();
        Date now = new Date(), cutoff = new Date(now.getTime()-5000);
        assertEquals(1,tasks.claimPoll(taskId,now,cutoff,"old-token"));
        jdbc.update("UPDATE out_painting_task SET lastPollTime = NULL WHERE id = ?",taskId);
        assertEquals(1,tasks.claimPoll(taskId,now,cutoff,"new-token"));
        assertEquals(1,tasks.completePoll(taskId,"new-token","SUCCEEDED","https://example.invalid/result.png",null,null,now));
        assertEquals(0,tasks.completePoll(taskId,"old-token","RUNNING",null,null,null,now));
        assertEquals("SUCCEEDED",tasks.selectById(taskId).getStatus());
        Long pending = outPainting.createTask(createRequest(source(null)),owner).getId();
        assertEquals(1,tasks.claimPoll(pending,now,cutoff,"first"));
        assertEquals(0,tasks.claimPoll(pending,now,cutoff,"second"));
    }


    @Test void pollFromStalePendingSnapshotCannotRegressDatabaseRunningStatus() {
        Long taskId = outPainting.createTask(createRequest(source(null)),owner).getId();
        // Poll A reads PENDING, then pauses before claiming its polling lease.
        OutPaintingTask staleSnapshot = tasks.selectById(taskId);
        assertEquals("PENDING",staleSnapshot.getStatus());
        Date firstTime = new Date();
        assertEquals(1,tasks.claimPoll(taskId,firstTime,new Date(firstTime.getTime()-5000),"poll-b"));
        // Poll B observes RUNNING and commits while A still holds the old PENDING snapshot.
        assertEquals(1,tasks.completePoll(taskId,"poll-b","RUNNING",null,null,null,firstTime));
        assertEquals("RUNNING",tasks.selectById(taskId).getStatus());
        Date laterTime = new Date(firstTime.getTime()+6000);
        assertEquals(1,tasks.claimPoll(taskId,laterTime,new Date(laterTime.getTime()-5000),"poll-a"));
        // A now owns the newest token, but its provider response still reports PENDING.
        // Token comparison alone cannot reject this update; the database transition must preserve RUNNING.
        assertEquals(1,tasks.completePoll(taskId,"poll-a",staleSnapshot.getStatus(),null,null,null,laterTime));
        assertEquals("RUNNING",tasks.selectById(taskId).getStatus());
    }


    @Test void unlistedRegisteredUserCannotReserveOrSubmitBillableTask() {
        User unlisted = new User(); unlisted.setId(2L); unlisted.setUserAccount("unlisted-ai-user");
        unlisted.setUserPassword("test-hash"); unlisted.setUserRole("user"); users.save(unlisted);
        Long sourceId = pictures.uploadPicture(new Object(),new PictureUploadRequest(),unlisted).getId();
        // Similar IDs must not grant substring or numeric-normalized access.
        properties.setAllowedUserIds("12,20,002");
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(),assertThrows(BusinessException.class,
                () -> outPainting.createTask(createRequest(sourceId),unlisted)).getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM out_painting_task",Integer.class));
        verifyNoInteractions(provider);
    }

    @Test void emptyAllowlistAllowsOnlyAdministratorsToCreateNewTasks() {
        properties.setAllowedUserIds("");
        Long sourceId = source(null);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(),assertThrows(BusinessException.class,
                () -> outPainting.createTask(createRequest(sourceId),owner)).getCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM out_painting_task",Integer.class));
        verifyNoInteractions(provider);
        owner.setUserRole("admin"); users.updateById(owner);
        assertEquals("PENDING",outPainting.createTask(createRequest(sourceId),users.getById(owner.getId())).getStatus());
        verify(provider,times(1)).create(anyString(),any());
    }

    @Test void removingCreationPermissionDoesNotBlockExistingTaskQueryOrSave() {
        Long taskId = outPainting.createTask(createRequest(source(null)),owner).getId();
        properties.setAllowedUserIds("");
        assertEquals("SUCCEEDED",outPainting.getTask(taskId,owner).getStatus());
        Long savedPictureId = outPainting.saveResult(saveRequest(taskId),owner).getId();
        assertEquals(savedPictureId,outPainting.saveResult(saveRequest(taskId),owner).getId());
        verify(urls,times(1)).uploadPicture(any(),anyString());
    }

    @Test void disabledCreationHasNoDatabaseOrProviderSideEffect() {
        properties.setEnabled(false);
        assertThrows(BusinessException.class, () -> outPainting.createTask(createRequest(1L),owner));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM out_painting_task",Integer.class));
        verifyNoInteractions(provider);
    }
}
