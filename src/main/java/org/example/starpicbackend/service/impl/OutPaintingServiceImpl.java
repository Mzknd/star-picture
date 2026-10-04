package org.example.starpicbackend.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.starpicbackend.api.outpainting.*;
import org.example.starpicbackend.config.OutPaintingProperties;
import org.example.starpicbackend.constant.UserConstant;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.mapper.OutPaintingTaskMapper;
import org.example.starpicbackend.model.dto.outpainting.*;
import org.example.starpicbackend.model.dto.picture.PictureUploadRequest;
import org.example.starpicbackend.model.entity.*;
import org.example.starpicbackend.model.enums.OutPaintingStatus;
import org.example.starpicbackend.model.vo.OutPaintingTaskVO;
import org.example.starpicbackend.model.vo.PictureVO;
import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.service.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/** Task ownership, admission limits, monotonic polling, and transactional save orchestration. */
@Service
public class OutPaintingServiceImpl implements OutPaintingService {
    private static final ObjectMapper TASK_JSON = new ObjectMapper();
    private static final ZoneId DAILY_LIMIT_ZONE = ZoneId.of("Asia/Shanghai");
    private final OutPaintingTaskMapper tasks;
    private final OutPaintingProvider provider;
    private final OutPaintingProperties properties;
    private final PictureService pictures;
    private final SpaceService spaces;
    private final CosManager cos;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutPaintingServiceImpl(OutPaintingTaskMapper tasks, OutPaintingProvider provider,
            OutPaintingProperties properties, PictureService pictures, SpaceService spaces, CosManager cos,
            TransactionTemplate transactions, @Qualifier("outPaintingClock") Clock clock) {
        this.tasks = tasks; this.provider = provider; this.properties = properties;
        this.pictures = pictures; this.spaces = spaces; this.cos = cos;
        this.transactions = transactions; this.clock = clock;
    }

    @Override
    public OutPaintingTaskVO createTask(CreateOutPaintingTaskRequest request, User user) {
        requireLogin(user);
        requireEnabled();
        if (!UserConstant.ADMIN_ROLE.equals(user.getUserRole()) && !properties.allowsUser(user.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "该账号未获得AI扩图创建权限");
        }
        if (request == null || request.getPictureId() == null || request.getPictureId() <= 0 || request.getParameters() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "需要有效的源图片和扩图参数");
        }
        Picture source = pictures.getById(request.getPictureId());
        pictures.checkPictureAuth(user, source);
        validateSource(source);
        request.getParameters().validate(source);
        String parametersJson = serialize(request.getParameters());
        OutPaintingTask task = transactions.execute(tx -> {
            // A database row lock works across app instances; no distributed component is required.
            if (tasks.lockUser(user.getId()) == null) { throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR); }
            Instant now = clock.instant();
            LocalDate day = now.atZone(DAILY_LIMIT_ZONE).toLocalDate();
            Date start = Date.from(day.atStartOfDay(DAILY_LIMIT_ZONE).toInstant());
            Date end = Date.from(day.plusDays(1).atStartOfDay(DAILY_LIMIT_ZONE).toInstant());
            if (tasks.countDaily(user.getId(), start, end) >= properties.getDailyLimit()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "今日扩图任务次数已用完");
            }
            if (tasks.countActive(user.getId(), Date.from(now)) >= properties.getActiveLimit()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "进行中的扩图任务已达上限");
            }
            OutPaintingTask reservation = new OutPaintingTask();
            reservation.setUserId(user.getId()); reservation.setPictureId(source.getId()); reservation.setSpaceId(source.getSpaceId());
            reservation.setStatus(OutPaintingStatus.SUBMITTING.name()); reservation.setParametersJson(parametersJson);
            reservation.setCreateTime(Date.from(now)); reservation.setUpdateTime(Date.from(now));
            reservation.setExpiresAt(Date.from(now.plus(Duration.ofHours(24))));
            if (tasks.insert(reservation) != 1) { throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建本地扩图任务失败"); }
            return reservation;
        });
        if (task == null) { throw new BusinessException(ErrorCode.OPERATION_ERROR); }
        // A network submission never holds the user quota lock. Failures still consume the daily attempt budget.
        try {
            String imageUrl = source.getSpaceId() == null ? source.getUrl() : cos.signedUrl(source.getUrl(), 86400);
            ProviderTask submitted = provider.create(imageUrl, request.getParameters());
            String status = localProviderStatus(submitted.getStatus());
            String code = providerFailureCode(submitted, status);
            tasks.completeSubmission(task.getId(), submitted.getTaskId(), status, submitted.getOutputImageUrl(),
                    code, failureMessage(status), now());
        } catch (OutPaintingProviderException ex) {
            tasks.completeSubmission(task.getId(), null, OutPaintingStatus.FAILED.name(), null,
                    ex.getErrorCode(), "扩图任务提交失败，请稍后重新创建", now());
        } catch (RuntimeException ex) {
            // Signing or an unexpected provider error must not leave a permanently active reservation.
            tasks.completeSubmission(task.getId(), null, OutPaintingStatus.FAILED.name(), null,
                    "SUBMISSION_FAILED", "扩图任务提交失败，请稍后重新创建", now());
        }
        tasks.expire(task.getId(), now());
        return OutPaintingTaskVO.from(ownedTask(task.getId(), user));
    }

    @Override
    public OutPaintingTaskVO getTask(Long localTaskId, User user) {
        OutPaintingTask task = ownedTask(localTaskId, user);
        tasks.expire(localTaskId, now());
        task = tasks.selectById(localTaskId);
        if (OutPaintingStatus.valueOf(task.getStatus()).polling() && properties.isEnabled()) {
            Date queryTime = now();
            Date cutoff = new Date(queryTime.getTime() - Math.max(1, properties.getPollIntervalSeconds()) * 1000L);
            String token = UUID.randomUUID().toString();
            if (tasks.claimPoll(localTaskId, queryTime, cutoff, token) == 1) {
                try {
                    ProviderTask response = provider.query(task.getProviderTaskId());
                    String status = localProviderStatus(response.getStatus());
                    // Queueing must never regress a task that was already observed running.
                    if (OutPaintingStatus.RUNNING.name().equals(task.getStatus()) && OutPaintingStatus.PENDING.name().equals(status)) {
                        status = OutPaintingStatus.RUNNING.name();
                    }
                    tasks.completePoll(localTaskId, token, status, response.getOutputImageUrl(),
                            providerFailureCode(response, status), failureMessage(status), now());
                } catch (OutPaintingProviderException ex) {
                    // Temporary HTTP errors do not falsely mark an in-flight cloud job as failed.
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图任务查询暂时失败，请稍后重试");
                }
            }
        }
        tasks.expire(localTaskId, now());
        return OutPaintingTaskVO.from(ownedTask(localTaskId, user));
    }

    @Override
    public PictureVO saveResult(SaveOutPaintingTaskRequest request, User user) {
        requireLogin(user);
        if (request == null || request.getTaskId() == null || request.getTaskId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (request.getPicName() != null && (request.getPicName().isBlank() || request.getPicName().length() > 128)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片名称需为1至128个字符");
        }
        // Poll before starting the save transaction; provider querying does not hold the save lock.
        getTask(request.getTaskId(), user);
        PictureVO response = transactions.execute(tx -> {
            OutPaintingTask locked = tasks.selectForUpdate(request.getTaskId());
            authorize(locked, user);
            if (locked.getSavedPictureId() != null) {
                Picture saved = pictures.getById(locked.getSavedPictureId());
                pictures.checkPictureAuth(user, saved);
                return pictures.getPictureVO(saved, null);
            }
            if (!locked.getExpiresAt().after(now())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图结果已超过24小时有效期");
            }
            if (!OutPaintingStatus.SUCCEEDED.name().equals(locked.getStatus()) || locked.getOutputImageUrl() == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图任务尚未成功，无法保存");
            }
            PictureUploadRequest upload = new PictureUploadRequest();
            upload.setSpaceId(locked.getSpaceId());
            upload.setPicName(request.getPicName() == null ? "AI扩图" : request.getPicName());
            // This download holds only this task lock. Existing upload code locks space -> picture,
            // shares this transaction, checks byte/count quotas, and compensates COS on outer rollback.
            PictureVO uploaded = pictures.uploadPicture(locked.getOutputImageUrl(), upload, user);
            if (tasks.bindSavedPicture(locked.getId(), uploaded.getId(), now()) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存扩图结果失败");
            }
            return uploaded;
        });
        if (response == null) { throw new BusinessException(ErrorCode.OPERATION_ERROR); }
        return response;
    }

    private OutPaintingTask ownedTask(Long taskId, User user) {
        requireLogin(user);
        if (taskId == null || taskId <= 0) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
        OutPaintingTask task = tasks.selectById(taskId);
        authorize(task, user);
        return task;
    }

    private void authorize(OutPaintingTask task, User user) {
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        // Ownership is strict even for administrators. Provider task IDs are never a user input.
        if (!Objects.equals(task.getUserId(), user.getId())) { throw new BusinessException(ErrorCode.NO_AUTH_ERROR); }
        if (task.getSpaceId() != null) { spaces.checkSpaceAuth(user, spaces.getById(task.getSpaceId())); }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) { throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI扩图功能未启用"); }
        if (properties.getDailyLimit() < 1 || properties.getActiveLimit() < 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI扩图任务限额配置错误");
        }
    }

    private static void requireLogin(User user) {
        if (user == null || user.getId() == null) { throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR); }
    }

    private static void validateSource(Picture source) {
        if (source.getPicWidth() == null || source.getPicHeight() == null || source.getPicSize() == null
                || source.getPicWidth() < 512 || source.getPicWidth() > 4096
                || source.getPicHeight() < 512 || source.getPicHeight() > 4096
                || source.getPicSize() <= 0 || source.getPicSize() > 10L * 1024 * 1024
                || source.getPicFormat() == null
                || !Set.of("jpg", "jpeg", "png", "heif", "webp").contains(source.getPicFormat().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "扩图源图需为512至4096像素、10MB以内的受支持图片");
        }
    }

    private static String serialize(OutPaintingParameters parameters) {
        try { return TASK_JSON.writeValueAsString(parameters); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "扩图参数无法序列化"); }
    }

    private static String localProviderStatus(String status) {
        if ("UNKNOWN".equals(status)) { return OutPaintingStatus.FAILED.name(); }
        if (status == null || !Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED").contains(status)) {
            throw new OutPaintingProviderException("INVALID_PROVIDER_RESPONSE");
        }
        return status;
    }

    private static String providerFailureCode(ProviderTask task, String status) {
        if (!Set.of("FAILED", "CANCELED").contains(status)) { return null; }
        return task.getErrorCode() == null ? ("UNKNOWN".equals(task.getStatus()) ? "PROVIDER_UNKNOWN_TASK" : "PROVIDER_TASK_FAILED")
                : task.getErrorCode();
    }

    private static String failureMessage(String status) {
        return "FAILED".equals(status) ? "扩图任务执行失败" : "CANCELED".equals(status) ? "扩图任务已取消" : null;
    }

    private Date now() { return Date.from(clock.instant()); }
}
