package org.example.starpicbackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** Provider identifiers and temporary result URLs stay in the server-side task record. */
@Data
@TableName("out_painting_task")
public class OutPaintingTask implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long pictureId;
    private Long spaceId;
    private String providerTaskId;
    private String status;
    private String parametersJson;
    private String outputImageUrl;
    private String errorCode;
    private String errorMessage;
    private Long savedPictureId;
    private Date createTime;
    private Date updateTime;
    private Date expiresAt;
    private Date lastPollTime;
    private String pollToken;
}
