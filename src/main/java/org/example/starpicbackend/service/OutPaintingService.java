package org.example.starpicbackend.service;

import org.example.starpicbackend.model.dto.outpainting.CreateOutPaintingTaskRequest;
import org.example.starpicbackend.model.dto.outpainting.SaveOutPaintingTaskRequest;
import org.example.starpicbackend.model.entity.User;
import org.example.starpicbackend.model.vo.OutPaintingTaskVO;
import org.example.starpicbackend.model.vo.PictureVO;

public interface OutPaintingService {
    OutPaintingTaskVO createTask(CreateOutPaintingTaskRequest request, User user);
    OutPaintingTaskVO getTask(Long localTaskId, User user);
    PictureVO saveResult(SaveOutPaintingTaskRequest request, User user);
}
