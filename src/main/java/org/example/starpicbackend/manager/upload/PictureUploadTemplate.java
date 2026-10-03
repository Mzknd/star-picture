package org.example.starpicbackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HttpRequest;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.config.CosClientConfig;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.model.dto.file.UploadPictureResult;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Slf4j
public abstract class PictureUploadTemplate {  
  
    @Resource
    protected CosManager cosManager;
  
    @Resource  
    protected CosClientConfig cosClientConfig;
  
    /**  
     * 模板方法，定义上传流程  
     */  
    public final UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        // 1. 校验图片
        validPicture(inputSource);

        // 2. 图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originFilename = getOriginFilename(inputSource);
        // 根据 inputSource 类型获取文件类型
        String fileType;
        if (inputSource instanceof String) {
            // URL 类型：发送 HEAD 请求，获取 HTTP 响应头
            String contentType = HttpRequest.head((String) inputSource)
                    .timeout(5000) // 设置超时，单位毫秒
                    .execute()
                    .header("Content-Type");
            if (contentType != null && contentType.contains("/")) {
                fileType = contentType.substring(contentType.lastIndexOf("/") + 1);
            } else {
                fileType = FileUtil.getSuffix(originFilename);
            }
            System.out.println("当前的后缀" + fileType);
            // 允许上传的文件后缀
            final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpeg", "jpg", "png", "webp");
            if (!ALLOW_FORMAT_LIST.contains(fileType)) {
                fileType = FileUtil.getSuffix(originFilename);
            }
        } else if (inputSource instanceof MultipartFile) {
            // MultipartFile 类型：从文件名获取后缀
            fileType = FileUtil.getSuffix(originFilename);
        } else {
            // 其他类型：从文件名获取后缀
            fileType = FileUtil.getSuffix(originFilename);
        }
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, fileType);
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);
  
        File file = null;
        try {
            // 创建临时文件
            file = File.createTempFile(uploadPath, null);
            // 处理文件来源（本地或 URL）
            processFile(inputSource, file);
            // 上传图片到对象存储
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)) {
                CIObject compressedCiObject = objectList.get(0);
                // 缩略图默认等于压缩图
                CIObject thumbnailCiObject = compressedCiObject;
                // 有生成缩略图，才得到缩略图
                if (objectList.size() > 1) {
                    thumbnailCiObject = objectList.get(1);
                }
                // 封装压缩图返回结果
                UploadPictureResult result = buildResult(originFilename, compressedCiObject, thumbnailCiObject, imageInfo);
                result.setOriginalKey(uploadPath);
                return result;
            }


            // 封装原图返回结果
            UploadPictureResult result = buildResult(originFilename, file, uploadPath, imageInfo);
            result.setOriginalKey(uploadPath);
            return result;
        } catch (Exception e) {
            log.error("图片上传到对象存储失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }
        finally {
            // 6. 清理临时文件  
            deleteTempFile(file);  
        }  
    }  
  
    /**  
     * 校验输入源（本地文件或 URL）  
     */  
    protected abstract void validPicture(Object inputSource);  
  
    /**  
     * 获取输入源的原始文件名  
     */  
    protected abstract String getOriginFilename(Object inputSource);  
  
    /**  
     * 处理输入源并生成本地临时文件  
     */  
    protected abstract void processFile(Object inputSource, File file) throws Exception;  
  
    /**  
     * 封装返回结果  
     */  
    private UploadPictureResult buildResult(String originFilename, File file, String uploadPath, ImageInfo imageInfo) {  
        UploadPictureResult uploadPictureResult = new UploadPictureResult();  
        int picWidth = imageInfo.getWidth();  
        int picHeight = imageInfo.getHeight();  
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));  
        uploadPictureResult.setPicWidth(picWidth);  
        uploadPictureResult.setPicHeight(picHeight);  
        uploadPictureResult.setPicScale(picScale);  
        uploadPictureResult.setPicFormat(imageInfo.getFormat());  
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);  
        return uploadPictureResult;  
    }

    /**
     * 封装返回结果
     * @param originFilename 原始文件名
     * @param compressedCiObject 压缩后的对象
     * @return
     */
//    private UploadPictureResult buildResult(String originFilename, CIObject compressedCiObject) {
//        UploadPictureResult uploadPictureResult = new UploadPictureResult();
//        int picWidth = compressedCiObject.getWidth();
//        int picHeight = compressedCiObject.getHeight();
//        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
//        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
//        uploadPictureResult.setPicWidth(picWidth);
//        uploadPictureResult.setPicHeight(picHeight);
//        uploadPictureResult.setPicScale(picScale);
//        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
//        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());
//        // 设置图片为压缩后的地址
//        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
//        return uploadPictureResult;
//    }

    /**
     * 封装返回结果
     * @param originFilename
     * @param compressedCiObject
     * @param thumbnailCiObject
     * @return
     */
    private UploadPictureResult buildResult(String originFilename, CIObject compressedCiObject, CIObject thumbnailCiObject, ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = compressedCiObject.getWidth();
        int picHeight = compressedCiObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());
        // 设置图片为压缩后的地址
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
        // 设置缩略图
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        return uploadPictureResult;
    }






    /**  
     * 删除临时文件  
     */  
    public void deleteTempFile(File file) {  
        if (file == null) {  
            return;  
        }  
        boolean deleteResult = file.delete();  
        if (!deleteResult) {  
            log.error("file delete error, filepath = {}", file.getAbsolutePath());  
        }  
    }  
}
