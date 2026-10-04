package org.example.starpicbackend.model.vo;

import lombok.Data;
import org.example.starpicbackend.model.entity.OutPaintingTask;
import org.example.starpicbackend.model.enums.OutPaintingStatus;
import java.io.Serializable;
import java.util.Date;

@Data
public class OutPaintingTaskVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long pictureId;
    private Long spaceId;
    private String status;
    private String outputImageUrl;
    private String errorCode;
    private String errorMessage;
    private Long savedPictureId;
    private Date createTime;
    private Date expiresAt;

    public static OutPaintingTaskVO from(OutPaintingTask task) {
        OutPaintingTaskVO vo = new OutPaintingTaskVO();
        vo.setId(task.getId()); vo.setPictureId(task.getPictureId()); vo.setSpaceId(task.getSpaceId());
        vo.setStatus(task.getStatus()); vo.setErrorCode(task.getErrorCode()); vo.setErrorMessage(task.getErrorMessage());
        vo.setSavedPictureId(task.getSavedPictureId()); vo.setCreateTime(task.getCreateTime()); vo.setExpiresAt(task.getExpiresAt());
        if (OutPaintingStatus.SUCCEEDED.name().equals(task.getStatus()) && task.getSavedPictureId() == null) {
            vo.setOutputImageUrl(task.getOutputImageUrl());
        }
        return vo;
    }
}
