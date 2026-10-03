package org.example.starpicbackend;

import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.manager.upload.FilePictureUpload;
import org.example.starpicbackend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadContentTest {
    @Test void misleadingExtensionIsRejectedBeforeCosUpload() {
        FilePictureUpload upload=new FilePictureUpload(); CosManager cos=mock(CosManager.class);
        ReflectionTestUtils.setField(upload,"cosManager",cos);
        MockMultipartFile file=new MockMultipartFile("file","fake.PNG","image/png","not-an-image".getBytes());
        assertThrows(BusinessException.class,()->upload.uploadPicture(file,"public/1"));
        verifyNoInteractions(cos);
    }
    @Test void derivativeKeysStayInsideSourceSpaceAndDoNotOverwriteWebpSource() {
        assertEquals("space/9/name_webp.webp",CosManager.webpKey("space/9/name.webp"));
        assertEquals("space/9/name_thumbnail.png",CosManager.thumbnailKey("space/9/name.png"));
    }
}
