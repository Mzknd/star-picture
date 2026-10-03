package org.example.starpicbackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import org.example.starpicbackend.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.util.Locale;
import java.util.Set;

@Service
public class FilePictureUpload extends PictureUploadTemplate {
    @Override protected void validPicture(Object source) {
        ThrowUtils.throwIf(!(source instanceof MultipartFile), ErrorCode.PARAMS_ERROR, "文件不能为空");
        MultipartFile file=(MultipartFile) source;
        ThrowUtils.throwIf(file.isEmpty() || file.getSize() > maxSizeBytes, ErrorCode.PARAMS_ERROR, "文件为空或大小超出限制");
        String suffix=FileUtil.getSuffix(file.getOriginalFilename());
        ThrowUtils.throwIf(suffix == null || !Set.of("jpg","jpeg","png","webp").contains(suffix.toLowerCase(Locale.ROOT)),
                ErrorCode.PARAMS_ERROR, "文件类型不支持");
    }
    @Override protected String getOriginFilename(Object source) { return ((MultipartFile)source).getOriginalFilename(); }
    @Override protected void processFile(Object source, File file) throws Exception { ((MultipartFile)source).transferTo(file); }
}
