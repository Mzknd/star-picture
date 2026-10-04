package org.example.starpicbackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.exception.ThrowUtils;
import org.example.starpicbackend.manager.CosManager;
import org.example.starpicbackend.manager.PictureFileCleanup;
import org.example.starpicbackend.manager.PublicPictureCache;
import org.example.starpicbackend.mapper.SpaceMapper;
import org.example.starpicbackend.utils.TransactionActions;
import org.example.starpicbackend.manager.FileManager;
import org.example.starpicbackend.manager.upload.FilePictureUpload;
import org.example.starpicbackend.manager.upload.PictureUploadTemplate;
import org.example.starpicbackend.manager.upload.UrlPictureUpload;
import org.example.starpicbackend.model.dto.file.UploadPictureResult;
import org.example.starpicbackend.model.dto.picture.*;
import org.example.starpicbackend.model.entity.Picture;
import org.example.starpicbackend.model.entity.Space;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.model.enums.PictureReviewStatusEnum;
import org.example.starpicbackend.model.vo.PictureVO;
import org.example.starpicbackend.model.vo.UserVO;
import org.example.starpicbackend.service.PictureService;
import org.example.starpicbackend.mapper.PictureMapper;
import org.example.starpicbackend.service.SpaceService;
import org.example.starpicbackend.service.UserService;
import org.example.starpicbackend.utils.ColorSimilarUtils;
import org.example.starpicbackend.utils.QueryRequestUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
* @author 十六夜⭐朔星
* @description 针对表【picture(图片)】的数据库操作Service实现
* @createDate 2025-12-07 16:32:56
*/
@Slf4j
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
    implements PictureService{


    @Resource
    private FileManager fileManager;

    @Resource
    private UserService userService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;

    @Resource
    private CosManager cosManager;

    @Resource
    private SpaceService spaceService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource private SpaceMapper spaceMapper;
    @Resource private PictureFileCleanup pictureFileCleanup;
    @Resource private PublicPictureCache publicPictureCache;


    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(inputSource == null || request == null, ErrorCode.PARAMS_ERROR);
        Long pictureId = request.getId();
        Picture existing = pictureId == null ? null : this.getById(pictureId);
        if (pictureId != null) {
            ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR);
            checkPictureAuth(loginUser, existing);
            ThrowUtils.throwIf(request.getSpaceId() != null && !Objects.equals(request.getSpaceId(), existing.getSpaceId()),
                    ErrorCode.PARAMS_ERROR, "不能通过重新上传移动图片所属空间");
        }
        Long spaceId = existing == null ? request.getSpaceId() : existing.getSpaceId();
        if (spaceId != null) {
            Space space = spaceService.getById(spaceId);
            spaceService.checkSpaceAuth(loginUser, space);
            if (existing == null) {
                ThrowUtils.throwIf(space.getTotalCount() >= space.getMaxCount()
                        || space.getTotalSize() >= space.getMaxSize(), ErrorCode.OPERATION_ERROR, "空间额度不足");
            }
        }
        String prefix = spaceId == null ? "public/" + loginUser.getId() : "space/" + spaceId;
        PictureUploadTemplate template = inputSource instanceof String ? urlPictureUpload : filePictureUpload;
        UploadPictureResult uploaded = template.uploadPicture(inputSource, prefix);
        Picture picture = new Picture();
        picture.setSpaceId(spaceId); picture.setUrl(uploaded.getUrl());
        picture.setThumbnailUrl(uploaded.getThumbnailUrl()); picture.setOriginalKey(uploaded.getOriginalKey());
        picture.setName(StrUtil.isNotBlank(request.getPicName()) ? request.getPicName() : uploaded.getPicName());
        picture.setPicSize(uploaded.getPicSize()); picture.setPicWidth(uploaded.getPicWidth());
        picture.setPicHeight(uploaded.getPicHeight()); picture.setPicScale(uploaded.getPicScale());
        picture.setPicFormat(uploaded.getPicFormat()); picture.setPicColor(uploaded.getPicColor());
        Picture[] replaced = new Picture[1];
        try {
            transactionTemplate.execute(status -> {
                // 全部修改按空间 -> 图片的顺序加行锁，避免与删除操作形成反向锁顺序。
                if (spaceId != null) { spaceService.checkSpaceAuth(loginUser, spaceMapper.selectForUpdate(spaceId)); }
                Picture old = pictureId == null ? null : getBaseMapper().selectForUpdate(pictureId);
                if (pictureId != null) {
                    ThrowUtils.throwIf(old == null, ErrorCode.NOT_FOUND_ERROR);
                    checkPictureAuth(loginUser, old);
                    ThrowUtils.throwIf(!Objects.equals(spaceId, old.getSpaceId()), ErrorCode.OPERATION_ERROR, "图片状态已改变");
                    picture.setId(pictureId); picture.setEditTime(new Date());
                    picture.setUserId(old.getUserId()); replaced[0] = old;
                } else { picture.setUserId(loginUser.getId()); }
                ThrowUtils.throwIf(StrUtil.isBlank(picture.getName()) || picture.getName().length() > 128,
                        ErrorCode.PARAMS_ERROR, "图片名称不合法");
                fillReviewParams(picture, loginUser);
                long sizeDelta = uploaded.getPicSize() - (old == null ? 0 : old.getPicSize());
                if (spaceId != null) {
                    int changed = spaceMapper.adjustQuota(spaceId, sizeDelta, old == null ? 1 : 0);
                    ThrowUtils.throwIf(changed != 1, ErrorCode.OPERATION_ERROR, "空间额度不足");
                }
                boolean saved = old == null ? save(picture) : updateById(picture);
                ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "图片保存失败");
                return null;
            });
        } catch (RuntimeException e) { pictureFileCleanup.cleanup(picture); throw e; }
        if(spaceId==null) {publicPictureCache.invalidateAfterCommit();}
        TransactionActions.afterRollback(() -> pictureFileCleanup.cleanup(picture));
        if (replaced[0] != null) { TransactionActions.afterCommit(() -> pictureFileCleanup.cleanup(replaced[0])); }
        PictureVO response = PictureVO.objToVo(picture);
        signPrivateUrls(response);
        return response;
    }


    /**
     * @param pictureQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        QueryRequestUtils.validateSort(pictureQueryRequest, "id", "name", "createTime", "editTime", "picSize", "picWidth", "picHeight", "picScale");
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = StrUtil.blankToDefault(pictureQueryRequest.getSortField(), "createTime");
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();


        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime), "editTime", startEditTime);
        queryWrapper.lt(ObjUtil.isNotEmpty(endEditTime), "editTime", endEditTime);

        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), "ascend".equals(sortOrder), sortField);
        if(!"id".equals(sortField)) {queryWrapper.orderByDesc("id");}
        return queryWrapper;
    }

    /**
     * 获取图片包装类（单条）
     * @param picture
     * @param request
     * @return
     */
    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        signPrivateUrls(pictureVO);
        return pictureVO;
    }
    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }
        // 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOList.forEach(this::signPrivateUrls);
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
    }

    private void signPrivateUrls(PictureVO picture) {
        if (picture.getSpaceId()!=null) {
            picture.setUrl(cosManager.signedUrl(picture.getUrl(),600));
            picture.setThumbnailUrl(cosManager.signedUrl(picture.getThumbnailUrl(),600));
        }
    }

    /**
     * 数据校验
     * @param picture
     */
    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        ThrowUtils.throwIf(picture.getName() != null && (StrUtil.isBlank(picture.getName()) || picture.getName().length() > 128), ErrorCode.PARAMS_ERROR, "名称长度不合法");
        ThrowUtils.throwIf(picture.getCategory() != null && picture.getCategory().length() > 64, ErrorCode.PARAMS_ERROR, "分类过长");
        ThrowUtils.throwIf(picture.getTags() != null && picture.getTags().length() > 512, ErrorCode.PARAMS_ERROR, "标签过长");
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 512, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 512, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    /**
     * 图片审核
     * @param pictureReviewRequest
     * @param loginUser
     */
    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        ThrowUtils.throwIf(pictureReviewRequest==null,ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser==null || !userService.isAdmin(loginUser),ErrorCode.NO_AUTH_ERROR);
        //1.校验参数
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || id<=0 || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 2.判断图片是否存在
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(StrUtil.isNotBlank(pictureReviewRequest.getExpectedUrl())
                && !pictureReviewRequest.getExpectedUrl().equals(oldPicture.getUrl()),
                ErrorCode.OPERATION_ERROR,"图片已替换，请刷新后重新审核");
        ThrowUtils.throwIf(pictureReviewRequest.getReviewMessage()!=null && pictureReviewRequest.getReviewMessage().length()>512,
                ErrorCode.PARAMS_ERROR,"审核说明过长");
        //3.校验审核状态是否重复
        // 已是该状态
        if (oldPicture.getReviewStatus().equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        // 更新审核状态
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(pictureReviewRequest, updatePicture);
        //4.数据库操作
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        Map<String,Object> snapshot=new HashMap<>();
        snapshot.put("id",id);snapshot.put("url",oldPicture.getUrl());snapshot.put("reviewStatus",oldPicture.getReviewStatus());
        snapshot.put("name",oldPicture.getName());snapshot.put("introduction",oldPicture.getIntroduction());
        snapshot.put("category",oldPicture.getCategory());snapshot.put("tags",oldPicture.getTags());
        boolean result = this.update(updatePicture,new QueryWrapper<Picture>().allEq(snapshot,true));
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR,"图片内容或审核状态已改变，请刷新后重新审核");
        if(oldPicture.getSpaceId()==null) {publicPictureCache.invalidateAfterCommit();}
    }

    /**
     * 填充审核参数
     *
     * @param picture
     * @param loginUser
     */
    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (picture.getSpaceId() != null || userService.isAdmin(loginUser)) {
            // 私有图片无需公共审核；管理员上传公共图片自动过审
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage(picture.getSpaceId() == null ? "管理员自动过审" : "私有空间图片");
            picture.setReviewTime(new Date());
        } else {
            // 非管理员，创建或编辑都要改为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }
    /**
     * 批量抓取和创建图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return 成功创建的图片数
     */

    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        String searchText = pictureUploadByBatchRequest.getSearchText();
        // 格式化数量
        Integer count = pictureUploadByBatchRequest.getCount();
        //名称前缀默认等于搜索关键词
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }

        ThrowUtils.throwIf(StrUtil.isBlank(searchText) || count==null || count<1 || count>30,
                ErrorCode.PARAMS_ERROR, "关键词不能为空，数量必须为 1-30");
        // 要抓取的地址
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", java.net.URLEncoder.encode(searchText, java.nio.charset.StandardCharsets.UTF_8));
        Document document;
        try {
            document = Jsoup.connect(fetchUrl).timeout(10000).get();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        Elements imgElementList = div.select("img.mimg");
        int uploadCount = 0;
        for (Element imgElement : imgElementList) {
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过: {}", fileUrl);
                continue;
            }
            // 上传图片
            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            if (StrUtil.isNotBlank(namePrefix)) {
                // 设置图片名称，序号连续递增
                pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            }
            try {
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
                log.info("图片上传成功, id = {}", pictureVO.getId());
                uploadCount++;
            } catch (Exception e) {
                log.error("图片上传失败", e);
                continue;
            }
            if (uploadCount >= count) {
                break;
            }
        }
        return uploadCount;
    }

    /**
     * 图片清理方法
     * @param oldPicture
     */
    @Override
    public void clearPictureFile(Picture oldPicture) {
        pictureFileCleanup.cleanup(oldPicture);
    }

    /**
     * 校验空间图片的权限
     * @param loginUser
     * @param picture
     */
    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            ThrowUtils.throwIf(!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser),
                    ErrorCode.NO_AUTH_ERROR);
        } else {
            Space space = spaceService.getById(spaceId);
            spaceService.checkSpaceAuth(loginUser, space);
        }
    }

    /**
     * 删除图片
     * @param pictureId
     * @param loginUser
     */
    @Override
    public void deletePicture(long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        Picture initial = this.getById(pictureId);
        ThrowUtils.throwIf(initial == null, ErrorCode.NOT_FOUND_ERROR);
        checkPictureAuth(loginUser, initial);
        Picture deleted = transactionTemplate.execute(status -> {
            Long spaceId = initial.getSpaceId();
            if (spaceId != null) { spaceService.checkSpaceAuth(loginUser, spaceMapper.selectForUpdate(spaceId)); }
            Picture old = getBaseMapper().selectForUpdate(pictureId);
            ThrowUtils.throwIf(old == null, ErrorCode.NOT_FOUND_ERROR);
            checkPictureAuth(loginUser, old);
            ThrowUtils.throwIf(!removeById(pictureId), ErrorCode.OPERATION_ERROR);
            if (spaceId != null) {
                ThrowUtils.throwIf(spaceMapper.adjustQuota(spaceId, -old.getPicSize(), -1) != 1,
                        ErrorCode.OPERATION_ERROR, "空间额度更新失败");
            }
            return old;
        });
        if(initial.getSpaceId()==null) {publicPictureCache.invalidateAfterCommit();}
        TransactionActions.afterCommit(() -> pictureFileCleanup.cleanup(deleted));
    }

    /**
     * 编辑图片
     * @param pictureEditRequest
     * @param loginUser
     */
    @Override
    public void editPicture(PictureEditRequest pictureEditRequest, User loginUser) {
        // 在此处将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        // 设置编辑时间
        picture.setEditTime(new Date());
        // 数据校验
        this.validPicture(picture);
        // 判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        checkPictureAuth(loginUser, oldPicture);
        // 补充审核参数
        picture.setSpaceId(oldPicture.getSpaceId());
        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        if(oldPicture.getSpaceId()==null) {publicPictureCache.invalidateAfterCommit();}
    }

    /**
     * 颜色搜图
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        // 1. 校验参数
        ThrowUtils.throwIf(spaceId == null || StrUtil.isBlank(picColor), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 2. 校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间访问权限");
        }
        // 3. 查询该空间下所有图片（必须有主色调）
        List<Picture> pictureList = this.lambdaQuery()
                .eq(Picture::getSpaceId, spaceId)
                .isNotNull(Picture::getPicColor)
                .list();
        // 如果没有图片，直接返回空列表
        if (CollUtil.isEmpty(pictureList)) {
            return Collections.emptyList();
        }
        // 将目标颜色转为 Color 对象
        ThrowUtils.throwIf(!picColor.matches("(?i)(?:#|0x)?[0-9a-f]{6}"), ErrorCode.PARAMS_ERROR, "请使用 6 位 RGB 十六进制颜色");
        Color targetColor = Color.decode(picColor.startsWith("#") || picColor.startsWith("0x") ? picColor : "#" + picColor);
        // 4. 计算相似度并排序
        List<Picture> sortedPictures = pictureList.stream()
                .sorted(Comparator.comparingDouble(picture -> {
                    // 提取图片主色调
                    String hexColor = picture.getPicColor();
                    // 没有主色调的图片放到最后
                    if (StrUtil.isBlank(hexColor)) {
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);
                    // 越大越相似
                    return -ColorSimilarUtils.calculateSimilarity(targetColor, pictureColor);
                }))
                // 取前 12 个
                .limit(12)
                .collect(Collectors.toList());

        // 转换为 PictureVO
        return sortedPictures.stream()
                .map(picture -> { PictureVO vo=PictureVO.objToVo(picture); signPrivateUrls(vo); return vo; })
                .collect(Collectors.toList());
    }

    /**
     * 批量修改图片
     * @param pictureEditByBatchRequest
     * @param loginUser
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser) {
        List<Long> pictureIdList = pictureEditByBatchRequest.getPictureIdList();
        Long spaceId = pictureEditByBatchRequest.getSpaceId();
        String category = pictureEditByBatchRequest.getCategory();
        List<String> tags = pictureEditByBatchRequest.getTags();

        // 1. 校验参数
        ThrowUtils.throwIf(spaceId == null || CollUtil.isEmpty(pictureIdList) || pictureIdList.size()>100,
                ErrorCode.PARAMS_ERROR, "每次只能编辑 1-100 张图片");
        ThrowUtils.throwIf(pictureIdList.stream().anyMatch(id->id==null || id<=0),ErrorCode.PARAMS_ERROR,"图片 ID 不合法");
        pictureIdList=pictureIdList.stream().distinct().collect(Collectors.toList());
        ThrowUtils.throwIf(category==null && tags==null && StrUtil.isBlank(pictureEditByBatchRequest.getNameRule()),
                ErrorCode.PARAMS_ERROR,"至少提供一个编辑字段");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 2. 校验空间权限
        Space space = spaceMapper.selectForUpdate(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间访问权限");
        }

        // 3. 查询指定图片，仅选择需要的字段
        List<Picture> pictureList = this.lambdaQuery()
                .select(Picture::getId, Picture::getSpaceId)
                .eq(Picture::getSpaceId, spaceId)
                .in(Picture::getId, pictureIdList)
                .orderByAsc(Picture::getId)
                .list();

        ThrowUtils.throwIf(pictureList.size()!=pictureIdList.size(),ErrorCode.PARAMS_ERROR,"部分图片不存在或不属于当前空间");
        // 4. 更新分类和标签
        pictureList.forEach(picture -> {
            if (category != null) {
                picture.setCategory(category);
            }
            if (tags != null) {
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        });
        // 批量重命名
        String nameRule = pictureEditByBatchRequest.getNameRule();
        fillPictureWithNameRule(pictureList, nameRule);
        pictureList.forEach(picture->{ picture.setEditTime(new Date()); validPicture(picture); });

        // 5. 批量更新
        boolean result = this.updateBatchById(pictureList);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }
    /**
     * nameRule 格式：图片{序号}
     *
     * @param pictureList
     * @param nameRule
     */
    private void fillPictureWithNameRule(List<Picture> pictureList, String nameRule) {
        if (CollUtil.isEmpty(pictureList) || StrUtil.isBlank(nameRule)) {
            return;
        }
        long count = 1;
        try {
            for (Picture picture : pictureList) {
                String pictureName = nameRule.replaceAll("\\{序号}", String.valueOf(count++));
                picture.setName(pictureName);
            }
        } catch (Exception e) {
            log.error("名称解析错误", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "名称解析错误");
        }
    }


}




