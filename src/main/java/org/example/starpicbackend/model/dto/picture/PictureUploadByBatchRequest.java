package org.example.starpicbackend.model.dto.picture;

import lombok.Data;

/**
 * 批量导入图片请求（管理员可用）
 */
@Data
public class PictureUploadByBatchRequest {  
  
    /**  
     * 搜索词  
     */  
    private String searchText;  
  
    /**  
     * 抓取数量  
     */  
    private Integer count = 10;
    /**
     * 名称前缀
     */
    private String namePrefix;

}
