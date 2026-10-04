package org.example.starpicbackend.model.dto.outpainting;

import lombok.Data;
import java.io.Serializable;

@Data
public class SaveOutPaintingTaskRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Local task identifier. Provider task IDs and arbitrary URLs are never accepted. */
    private Long taskId;
    private String picName;
}
