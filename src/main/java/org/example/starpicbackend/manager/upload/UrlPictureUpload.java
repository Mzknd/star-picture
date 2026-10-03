package org.example.starpicbackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.example.starpicbackend.exception.*;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.io.*;
import java.net.URI;
import java.util.Set;

@Service
public class UrlPictureUpload extends PictureUploadTemplate {
    @Resource private RemoteImagePolicy policy;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");
    @Override protected void validPicture(Object source) {
        ThrowUtils.throwIf(!(source instanceof String) || StrUtil.isBlank((String) source),
                ErrorCode.PARAMS_ERROR, "图片地址不能为空");
        String url = (String) source; policy.validate(url);
        try (HttpResponse response = HttpRequest.head(url).timeout(10000).setFollowRedirects(false).executeAsync()) {
            // 不支持 HEAD 的服务器使用有大小限制的 GET；其他错误直接拒绝。
            if (response.getStatus() == 405 || response.getStatus() == 501) { return; }
            ThrowUtils.throwIf(!response.isOk(), ErrorCode.PARAMS_ERROR, "图片 HEAD 请求失败或发生重定向");
            validateHeaders(response);
        }
    }
    private void validateHeaders(HttpResponse response) {
        String type = response.header("Content-Type");
        if (StrUtil.isNotBlank(type)) {
            type = type.split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            ThrowUtils.throwIf(!TYPES.contains(type), ErrorCode.PARAMS_ERROR, "远程文件不是支持的图片类型");
        }
        String length = response.header("Content-Length");
        if (StrUtil.isNotBlank(length)) {
            try { long size=Long.parseLong(length); ThrowUtils.throwIf(size < 0 || size > maxSizeBytes,
                    ErrorCode.PARAMS_ERROR, "图片大小超出限制"); }
            catch (NumberFormatException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片大小响应头无效"); }
        }
    }
    @Override protected String getOriginFilename(Object source) {
        String name = FileUtil.getName(URI.create((String)source).getPath());
        return StrUtil.isBlank(name) ? "remote-image" : name;
    }
    @Override protected void processFile(Object source, File file) throws Exception {
        String url=(String)source; policy.validate(url);
        try (HttpResponse response = HttpRequest.get(url).timeout(10000).setFollowRedirects(false).executeAsync()) {
            ThrowUtils.throwIf(!response.isOk(), ErrorCode.PARAMS_ERROR, "图片下载失败或发生重定向");
            validateHeaders(response);
            try (InputStream input = response.bodyStream(); OutputStream output = new FileOutputStream(file)) {
                byte[] buffer=new byte[8192]; long total=0; int read;
                while ((read=input.read(buffer)) != -1) {
                    total += read;
                    ThrowUtils.throwIf(total > maxSizeBytes, ErrorCode.PARAMS_ERROR, "图片实际下载大小超出限制");
                    output.write(buffer,0,read);
                }
            }
        }
    }
}
