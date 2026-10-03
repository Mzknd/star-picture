package org.example.starpicbackend;

import com.qcloud.cos.COSClient;
import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.manager.PictureFileCleanup;
import org.example.starpicbackend.manager.upload.FilePictureUpload;
import org.example.starpicbackend.manager.upload.UrlPictureUpload;
import org.example.starpicbackend.model.dto.file.UploadPictureResult;
import org.example.starpicbackend.model.dto.picture.PictureUploadRequest;
import org.example.starpicbackend.model.dto.space.SpaceAddRequest;
import org.example.starpicbackend.model.entity.*;
import org.example.starpicbackend.service.*;
import org.example.starpicbackend.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class QuotaTransactionTest {
    @Autowired PictureService pictures;
    @Autowired SpaceService spaces;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @MockBean COSClient cosClient;
    @MockBean CosManager cosManager;
    @MockBean FilePictureUpload files;
    @MockBean UrlPictureUpload urls;
    @MockBean PictureFileCleanup cleanup;
    User owner;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM picture"); jdbc.update("DELETE FROM space"); jdbc.update("DELETE FROM user");
        owner = new User(); owner.setId(1L); owner.setUserAccount("quota-owner");
        owner.setUserPassword("test-hash"); owner.setUserRole("user"); users.save(owner);
        UploadPictureResult result = new UploadPictureResult(); result.setPicName("sample"); result.setPicSize(60L);
        result.setUrl("https://example.invalid/image.webp"); result.setPicFormat("webp");
        when(files.uploadPicture(any(),anyString())).thenReturn(result);
    }
    Space space(long maxCount,long maxSize) {
        SpaceAddRequest add = new SpaceAddRequest(); long id = spaces.addSpace(add,owner);
        Space s = spaces.getById(id); s.setMaxCount(maxCount); s.setMaxSize(maxSize); spaces.updateById(s); return s;
    }
    PictureUploadRequest request(Long spaceId) { PictureUploadRequest r=new PictureUploadRequest(); r.setSpaceId(spaceId); return r; }
    @Test void capacityIncludesIncomingImageAndFailureRollsBackBothTables() {
        Space s=space(10,50);
        assertThrows(BusinessException.class,()->pictures.uploadPicture(new Object(),request(s.getId()),owner));
        assertEquals(0,pictures.count()); assertEquals(0,spaces.getById(s.getId()).getTotalCount());
        assertEquals(0,spaces.getById(s.getId()).getTotalSize()); verify(cleanup).cleanup(any());
    }
    @Test void replacingAtCountLimitUsesSizeDifferenceAndPreservesCreator() {
        Space s=space(1,100); long id=pictures.uploadPicture(new Object(),request(s.getId()),owner).getId();
        UploadPictureResult smaller=new UploadPictureResult(); smaller.setPicName("smaller"); smaller.setPicSize(20L);
        smaller.setUrl("https://example.invalid/smaller.webp"); when(files.uploadPicture(any(),anyString())).thenReturn(smaller);
        PictureUploadRequest replace=new PictureUploadRequest(); replace.setId(id);
        pictures.uploadPicture(new Object(),replace,owner);
        Space updated=spaces.getById(s.getId()); assertEquals(1,updated.getTotalCount()); assertEquals(20,updated.getTotalSize());
        assertEquals(1L,pictures.getById(id).getUserId());
        pictures.deletePicture(id,owner); assertEquals(0,spaces.getById(s.getId()).getTotalSize());
    }
    @Test void concurrentUploadsCannotExceedLastAvailableSlot() throws Exception {
        Space s=space(1,1000); CyclicBarrier barrier=new CyclicBarrier(2);
        when(files.uploadPicture(any(),anyString())).thenAnswer(call->{ barrier.await(5,TimeUnit.SECONDS);
            UploadPictureResult result=new UploadPictureResult(); result.setPicName("parallel"); result.setPicSize(60L);
            result.setUrl("https://example.invalid/"+Thread.currentThread().getId()+".webp"); return result; });
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> upload=()->{try {pictures.uploadPicture(new Object(),request(s.getId()),owner);return true;}
                catch(BusinessException expected){return false;}};
            Future<Boolean> a=pool.submit(upload), b=pool.submit(upload);
            int successes=(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0);
            assertEquals(1,successes); assertEquals(1,pictures.count());
            assertEquals(1,spaces.getById(s.getId()).getTotalCount()); assertEquals(60,spaces.getById(s.getId()).getTotalSize());
        } finally {pool.shutdownNow();}
    }
    @Test void nonemptySpaceCannotBeDeleted() {
        Space s=space(10,1000); pictures.uploadPicture(new Object(),request(s.getId()),owner);
        assertThrows(BusinessException.class,()->spaces.deleteSpace(s.getId(),owner));
        assertNotNull(spaces.getById(s.getId()));
    }
    @Test void concurrentCreationLeavesOnlyOneSpace() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> add=()->{try { spaces.addSpace(new SpaceAddRequest(),owner);return true; }
                catch(BusinessException expected){return false;}};
            Future<Boolean> a=pool.submit(add),b=pool.submit(add);
            assertEquals(1,(a.get()?1:0)+(b.get()?1:0)); assertEquals(1,spaces.count());
        } finally {pool.shutdownNow();}
    }
}
