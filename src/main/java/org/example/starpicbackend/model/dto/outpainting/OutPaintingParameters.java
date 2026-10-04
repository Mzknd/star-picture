package org.example.starpicbackend.model.dto.outpainting;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.model.entity.Picture;

import java.io.Serializable;
import java.util.Set;

/** API clients use camelCase; the provider request is serialized separately with snake_case. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutPaintingParameters implements Serializable {
    private static final long serialVersionUID = 1L;
    private Integer angle;
    private String outputRatio;
    private Double xScale;
    private Double yScale;
    private Integer topOffset;
    private Integer bottomOffset;
    private Integer leftOffset;
    private Integer rightOffset;
    private Boolean bestQuality;
    private Boolean addWatermark;

    public void validate(Picture picture) {
        if (angle != null && (angle < 0 || angle > 359)) { invalid("angle范围为0至359"); }
        if (outputRatio != null && !Set.of("", "1:1", "3:4", "4:3", "9:16", "16:9").contains(outputRatio)) {
            invalid("outputRatio不支持该比例");
        }
        validateScale(xScale); validateScale(yScale);
        for (Integer offset : new Integer[]{topOffset, bottomOffset, leftOffset, rightOffset}) {
            if (offset != null && offset < 0) { invalid("扩展像素不能为负数"); }
        }
        boolean ratio = outputRatio != null && !outputRatio.isBlank();
        boolean scale = value(xScale) > 1 || value(yScale) > 1;
        boolean offset = pixels(leftOffset) + pixels(rightOffset) + pixels(topOffset) + pixels(bottomOffset) > 0;
        if (ratio && (scale || offset) || scale && offset) { invalid("请只选择一种扩图方式"); }
        if (!ratio && !scale && !offset) { invalid("至少设置一种有效的扩图参数"); }
        // Offsets are defined on the rotated image; defer rotated geometry constraints to the provider.
        if (offset && (angle == null || angle == 0)) {
            if (pixels(leftOffset) + pixels(rightOffset) >= 3L * picture.getPicWidth()
                    || pixels(topOffset) + pixels(bottomOffset) >= 3L * picture.getPicHeight()) {
                invalid("单方向总扩展像素需小于原图相应边长的3倍");
            }
        }
    }

    private static void validateScale(Double scale) {
        if (scale != null && (!Double.isFinite(scale) || scale < 1 || scale > 3)) { invalid("扩展比例范围为1至3"); }
    }
    private static double value(Double number) { return number == null ? 1 : number; }
    private static long pixels(Integer number) { return number == null ? 0 : number; }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
