package org.example.starpicbackend.mapper;

import org.example.starpicbackend.model.entity.Picture;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/**
* @author 十六夜⭐朔星
* @description 针对表【picture(图片)】的数据库操作Mapper
* @createDate 2025-12-07 16:32:56
* @Entity org.example.starpicbackend.model.entity.Picture
*/
public interface PictureMapper extends BaseMapper<Picture> {
    @Select("SELECT * FROM picture WHERE id = #{id} AND isDelete = 0 FOR UPDATE")
    Picture selectForUpdate(@Param("id") Long id);

}




