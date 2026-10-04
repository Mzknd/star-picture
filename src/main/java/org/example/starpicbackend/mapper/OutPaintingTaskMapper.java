package org.example.starpicbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.starpicbackend.model.entity.OutPaintingTask;

import java.util.Date;

public interface OutPaintingTaskMapper extends BaseMapper<OutPaintingTask> {
    /** Serializes task reservations across all app instances for one account. */
    @Select("SELECT id FROM user WHERE id = #{id} AND isDelete = 0 FOR UPDATE")
    Long lockUser(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM out_painting_task WHERE userId = #{userId} AND createTime >= #{start} AND createTime < #{end}")
    int countDaily(@Param("userId") Long userId, @Param("start") Date start, @Param("end") Date end);

    @Select("SELECT COUNT(*) FROM out_painting_task WHERE userId = #{userId} "
            + "AND status IN ('SUBMITTING','PENDING','RUNNING') AND expiresAt > #{now}")
    int countActive(@Param("userId") Long userId, @Param("now") Date now);

    @Select("SELECT * FROM out_painting_task WHERE id = #{id} FOR UPDATE")
    OutPaintingTask selectForUpdate(@Param("id") Long id);

    @Update("UPDATE out_painting_task SET status = 'EXPIRED', outputImageUrl = NULL, pollToken = NULL, "
            + "errorCode = 'TASK_EXPIRED', errorMessage = '扩图任务或结果已超过24小时有效期', updateTime = #{now} "
            + "WHERE id = #{id} AND savedPictureId IS NULL AND expiresAt <= #{now} "
            + "AND status IN ('SUBMITTING','PENDING','RUNNING','SUCCEEDED')")
    int expire(@Param("id") Long id, @Param("now") Date now);

    @Update("UPDATE out_painting_task SET lastPollTime = #{now}, pollToken = #{token} "
            + "WHERE id = #{id} AND expiresAt > #{now} AND status IN ('PENDING','RUNNING') "
            + "AND (lastPollTime IS NULL OR lastPollTime <= #{cutoff})")
    int claimPoll(@Param("id") Long id, @Param("now") Date now, @Param("cutoff") Date cutoff, @Param("token") String token);

    @Update("UPDATE out_painting_task SET status = CASE WHEN status = 'RUNNING' AND #{status} = 'PENDING' "
            + "THEN 'RUNNING' ELSE #{status} END, outputImageUrl = #{url}, "
            + "errorCode = #{code}, errorMessage = #{message}, updateTime = #{now}, pollToken = NULL "
            + "WHERE id = #{id} AND pollToken = #{token} AND expiresAt > #{now} AND status IN ('PENDING','RUNNING')")
    int completePoll(@Param("id") Long id, @Param("token") String token, @Param("status") String status,
                     @Param("url") String url, @Param("code") String code, @Param("message") String message, @Param("now") Date now);

    @Update("UPDATE out_painting_task SET providerTaskId = #{providerId}, status = #{status}, outputImageUrl = #{url}, "
            + "errorCode = #{code}, errorMessage = #{message}, updateTime = #{now} "
            + "WHERE id = #{id} AND status = 'SUBMITTING' AND expiresAt > #{now}")
    int completeSubmission(@Param("id") Long id, @Param("providerId") String providerId, @Param("status") String status,
                           @Param("url") String url, @Param("code") String code, @Param("message") String message,
                           @Param("now") Date now);

    @Update("UPDATE out_painting_task SET savedPictureId = #{pictureId}, outputImageUrl = NULL, updateTime = #{now} "
            + "WHERE id = #{id} AND status = 'SUCCEEDED' AND savedPictureId IS NULL")
    int bindSavedPicture(@Param("id") Long id, @Param("pictureId") Long pictureId, @Param("now") Date now);
}
