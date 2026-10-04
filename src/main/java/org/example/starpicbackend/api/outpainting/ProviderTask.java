package org.example.starpicbackend.api.outpainting;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProviderTask {
    private String taskId;
    private String status;
    private String outputImageUrl;
    private String errorCode;
}
