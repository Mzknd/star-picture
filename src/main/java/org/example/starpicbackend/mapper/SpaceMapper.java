package org.example.starpicbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import org.example.starpicbackend.model.entity.Space;

public interface SpaceMapper extends BaseMapper<Space> {
    @Select("SELECT * FROM space WHERE id = #{id} AND isDelete = 0 FOR UPDATE")
    Space selectForUpdate(@Param("id") Long id);

    @Update("UPDATE space SET totalSize = totalSize + #{sizeDelta}, totalCount = totalCount + #{countDelta} "
            + "WHERE id = #{id} AND isDelete = 0 "
            + "AND totalSize + #{sizeDelta} BETWEEN 0 AND maxSize "
            + "AND totalCount + #{countDelta} BETWEEN 0 AND maxCount")
    int adjustQuota(@Param("id") Long id, @Param("sizeDelta") long sizeDelta,
                    @Param("countDelta") long countDelta);
}
