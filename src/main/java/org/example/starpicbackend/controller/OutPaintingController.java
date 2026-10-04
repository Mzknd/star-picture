package org.example.starpicbackend.controller;

import lombok.RequiredArgsConstructor;
import org.example.starpicbackend.common.BaseResponse;
import org.example.starpicbackend.common.ResultUtils;
import org.example.starpicbackend.model.dto.outpainting.CreateOutPaintingTaskRequest;
import org.example.starpicbackend.model.dto.outpainting.SaveOutPaintingTaskRequest;
import org.example.starpicbackend.model.vo.OutPaintingTaskVO;
import org.example.starpicbackend.model.vo.PictureVO;
import org.example.starpicbackend.service.OutPaintingService;
import org.example.starpicbackend.service.UserService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/picture/out_painting")
public class OutPaintingController {
    private final OutPaintingService outPaintingService;
    private final UserService userService;

    @PostMapping("/create_task")
    public BaseResponse<OutPaintingTaskVO> createTask(@RequestBody CreateOutPaintingTaskRequest body, HttpServletRequest request) {
        return ResultUtils.success(outPaintingService.createTask(body, userService.getLoginUser(request)));
    }

    @GetMapping("/get_task")
    public BaseResponse<OutPaintingTaskVO> getTask(@RequestParam Long taskId, HttpServletRequest request) {
        return ResultUtils.success(outPaintingService.getTask(taskId, userService.getLoginUser(request)));
    }

    @PostMapping("/save")
    public BaseResponse<PictureVO> save(@RequestBody SaveOutPaintingTaskRequest body, HttpServletRequest request) {
        return ResultUtils.success(outPaintingService.saveResult(body, userService.getLoginUser(request)));
    }
}
