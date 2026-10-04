package org.example.starpicbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.starpicbackend.model.entity.Space;

public interface SpaceMapper extends BaseMapper<Space> {
    @Select("SELECT * FROM space WHERE id = #{id} AND isDelete = 0 FOR UPDATE")
    Space selectForUpdate(@Param("id") Long id);

    @Update("UPDATE space SET totalSize = totalSize + #{sizeDelta}, totalCount = totalCount + #{countDelta} "
            + "WHERE id = #{id} AND isDelete = 0 "
            + "AND totalSize + #{sizeDelta} >= 0 AND (#{sizeDelta} <= 0 OR totalSize + #{sizeDelta} <= maxSize) "
            + "AND totalCount + #{countDelta} >= 0 AND (#{countDelta} <= 0 OR totalCount + #{countDelta} <= maxCount)")
    int adjustQuota(@Param("id") Long id, @Param("sizeDelta") long sizeDelta,
                    @Param("countDelta") long countDelta);
}
