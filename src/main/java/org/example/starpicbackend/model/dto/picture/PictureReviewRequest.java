package org.example.starpicbackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

@Data
public class PictureReviewRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /** 审核页面加载时的图片 URL，防止图片替换后审核旧页面。 */
    private String expectedUrl;

    /**
     * 状态：0-待审核, 1-通过, 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;


    private static final long serialVersionUID = 1L;
}
