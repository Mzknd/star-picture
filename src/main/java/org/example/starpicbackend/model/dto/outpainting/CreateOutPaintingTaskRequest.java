package org.example.starpicbackend.model.dto.outpainting;

import lombok.Data;
import java.io.Serializable;

@Data
public class CreateOutPaintingTaskRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Existing, authorized source image; results keep this source's space. */
    private Long pictureId;
    private OutPaintingParameters parameters;
}
