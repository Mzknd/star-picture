package org.example.starpicbackend.manager;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.config.CosClientConfig;
import org.example.starpicbackend.mapper.PictureMapper;
import org.example.starpicbackend.model.entity.Picture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
public class PictureFileCleanup {
    @Resource private CosManager cosManager;
    @Resource private CosClientConfig config;
    @Resource private PictureMapper pictureMapper;

    @Async
    public void cleanup(Picture picture) {
        try {
            if (StrUtil.isNotBlank(picture.getUrl()) && pictureMapper.selectCount(
                    new QueryWrapper<Picture>().eq("url", picture.getUrl())) > 0) { return; }
            Set<String> keys = new LinkedHashSet<>();
            if (StrUtil.isNotBlank(picture.getOriginalKey())) { keys.add(picture.getOriginalKey()); }
            addUrlKey(keys, picture.getUrl()); addUrlKey(keys, picture.getThumbnailUrl());
            for (String key : keys) { deleteWithRetry(key); }
        } catch (Exception e) {
            log.error("图片资源回收失败，pictureId={}，请检查资源回收日志", picture.getId(), e);
        }
    }
    private void addUrlKey(Set<String> keys, String url) {
        if (StrUtil.isBlank(url)) { return; }
        URI uri = URI.create(url);
        if (!uri.getHost().equalsIgnoreCase(URI.create(config.getHost()).getHost())) {
            throw new IllegalArgumentException("拒绝删除非当前 COS 域名对象");
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) { keys.add(path.substring(1)); }
    }
    private void deleteWithRetry(String key) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { cosManager.deleteObject(key); return; }
            catch (Exception e) {
                if (attempt == 3) { log.error("COS 对象清理三次失败，key={}", key, e); }
            }
        }
    }
}
