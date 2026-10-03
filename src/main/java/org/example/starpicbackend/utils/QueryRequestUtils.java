package org.example.starpicbackend.utils;

import cn.hutool.core.util.StrUtil;
import org.example.starpicbackend.common.PageRequest;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.exception.ThrowUtils;
import java.util.Arrays;

/** 分页与排序的边界统一在服务端校验。 */
public final class QueryRequestUtils {
    private QueryRequestUtils() { }
    public static void validatePage(PageRequest request, int maxSize) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "请求不能为空");
        ThrowUtils.throwIf(request.getCurrent() < 1 || request.getPageSize() < 1
                || request.getPageSize() > maxSize, ErrorCode.PARAMS_ERROR, "分页参数超出范围");
    }
    public static void validateSort(PageRequest request, String... allowedFields) {
        ThrowUtils.throwIf(StrUtil.isNotBlank(request.getSortField())
                && !Arrays.asList(allowedFields).contains(request.getSortField()),
                ErrorCode.PARAMS_ERROR, "不支持的排序字段");
        ThrowUtils.throwIf(!"ascend".equals(request.getSortOrder())
                && !"descend".equals(request.getSortOrder()), ErrorCode.PARAMS_ERROR, "不支持的排序方向");
    }
}
