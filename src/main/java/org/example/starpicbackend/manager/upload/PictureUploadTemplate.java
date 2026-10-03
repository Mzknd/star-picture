package org.example.starpicbackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.config.CosClientConfig;
import org.example.starpicbackend.exception.*;
import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.manager.PictureFileCleanup;
import org.example.starpicbackend.model.dto.file.UploadPictureResult;
import org.example.starpicbackend.model.entity.Picture;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.*;

@Slf4j
public abstract class PictureUploadTemplate {
    @Resource protected CosManager cosManager;
    @Resource protected CosClientConfig cosClientConfig;
    @Resource private PictureFileCleanup cleanup;
    @Value("${picture.upload.max-bytes:10485760}") protected long maxSizeBytes=10485760;

    /** 固定流程：源校验 -> 获取文件 -> 内容校验 -> COS 处理 -> 返回元数据 -> 清理临时文件。 */
    public final UploadPictureResult uploadPicture(Object source, String prefix) {
        validPicture(source);
        File file=null; String key=null;
        try {
            file=File.createTempFile("star-picture-", ".tmp");
            processFile(source,file);
            String format=validateContent(file);
            key=prefix + "/" + UUID.randomUUID().toString().replace("-", "") + "." + format;
            PutObjectResult result=cosManager.putPictureObject(key,file);
            ImageInfo info=result.getCiUploadResult().getOriginalInfo().getImageInfo();
            List<CIObject> processed=result.getCiUploadResult().getProcessResults().getObjectList();
            UploadPictureResult upload=new UploadPictureResult(); upload.setOriginalKey(key);
            String name=FileUtil.mainName(getOriginFilename(source));
            if(name==null || name.isBlank()) {name="image";}
            upload.setPicName(name.length()>128 ? name.substring(0,128) : name);
            upload.setPicWidth(info.getWidth()); upload.setPicHeight(info.getHeight());
            upload.setPicScale(Math.round(info.getWidth()*100.0/info.getHeight())/100.0);
            String color=info.getAve();
            upload.setPicColor(color != null && color.startsWith("0x") ? "#"+color.substring(2) : color);
            if (processed!=null && !processed.isEmpty()) {
                CIObject image=processed.get(0), thumbnail=processed.size()>1?processed.get(1):image;
                upload.setUrl(cosManager.objectUrl(image.getKey()));
                upload.setThumbnailUrl(cosManager.objectUrl(thumbnail.getKey()));
                upload.setPicSize(image.getSize().longValue()); upload.setPicFormat(image.getFormat());
            } else {
                upload.setUrl(cosManager.objectUrl(key)); upload.setThumbnailUrl(upload.getUrl());
                upload.setPicSize(file.length()); upload.setPicFormat(format);
            }
            return upload;
        } catch (Exception e) {
            if (key!=null) {
                Picture orphan=new Picture(); orphan.setOriginalKey(key);
                orphan.setUrl(cosManager.objectUrl(CosManager.webpKey(key)));
                orphan.setThumbnailUrl(cosManager.objectUrl(CosManager.thumbnailKey(key)));
                cleanup.cleanup(orphan);
            }
            if(e instanceof BusinessException) {throw (BusinessException)e;}
            log.warn("图片上传或处理失败，未记录远程地址和密钥", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"图片上传或处理失败");
        } finally { deleteTempFile(file); }
    }
    private String validateContent(File file) throws Exception {
        ThrowUtils.throwIf(file.length()==0 || file.length()>maxSizeBytes, ErrorCode.PARAMS_ERROR,"文件为空或大小超限");
        try (ImageInputStream input=ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            ThrowUtils.throwIf(!readers.hasNext(), ErrorCode.PARAMS_ERROR,"文件内容不是可解析的图片");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                ThrowUtils.throwIf(!Set.of("jpeg","jpg","png","webp").contains(format),ErrorCode.PARAMS_ERROR,"图片格式不支持");
                long width=reader.getWidth(0), height=reader.getHeight(0);
                ThrowUtils.throwIf(width<=0 || height<=0 || width*height>40000000L,ErrorCode.PARAMS_ERROR,"图片像素数超限");
                reader.read(0); return format;
            } finally {reader.dispose();}
        }
    }
    protected abstract void validPicture(Object source);
    protected abstract String getOriginFilename(Object source);
    protected abstract void processFile(Object source, File file) throws Exception;
    public void deleteTempFile(File file) {
        if(file!=null && file.exists() && !file.delete()) {log.warn("临时图片清理失败: {}",file.getName());}
    }
}
