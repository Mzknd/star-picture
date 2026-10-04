package org.example.starpicbackend.api.outpainting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.starpicbackend.config.OutPaintingProperties;
import org.example.starpicbackend.model.dto.outpainting.OutPaintingParameters;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Official contract checked 2026-10-04:
 * https://help.aliyun.com/zh/model-studio/image-scaling-api
 * Provider calls submit asynchronous work; they never wait for image generation.
 */
public class AliyunOutPaintingClient implements OutPaintingProvider {
    private static final ObjectMapper PROVIDER_JSON = new ObjectMapper();
    private final OutPaintingProperties properties;
    private final RestTemplate http;

    public AliyunOutPaintingClient(OutPaintingProperties properties, RestTemplate http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public ProviderTask create(String imageUrl, OutPaintingParameters p) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        add(parameters, "angle", p.getAngle()); add(parameters, "output_ratio", p.getOutputRatio());
        add(parameters, "x_scale", p.getXScale()); add(parameters, "y_scale", p.getYScale());
        add(parameters, "top_offset", p.getTopOffset()); add(parameters, "bottom_offset", p.getBottomOffset());
        add(parameters, "left_offset", p.getLeftOffset()); add(parameters, "right_offset", p.getRightOffset());
        add(parameters, "best_quality", p.getBestQuality()); add(parameters, "add_watermark", p.getAddWatermark());
        // Keep output compatible with the existing maximum 10MB upload pipeline.
        parameters.put("limit_image_size", true);
        Map<String, Object> body = Map.of("model", "image-out-painting", "input", Map.of("image_url", imageUrl),
                "parameters", parameters);
        return exchange("/api/v1/services/aigc/image2image/out-painting", HttpMethod.POST, body, true);
    }

    @Override
    public ProviderTask query(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new OutPaintingProviderException("INVALID_PROVIDER_TASK");
        }
        ProviderTask response = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null, false);
        if (!taskId.equals(response.getTaskId())) { throw new OutPaintingProviderException("INVALID_PROVIDER_RESPONSE"); }
        return response;
    }

    private ProviderTask exchange(String path, HttpMethod method, Object body, boolean async) {
        if (!properties.isEnabled()) { throw new OutPaintingProviderException("FEATURE_DISABLED"); }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new OutPaintingProviderException("PROVIDER_NOT_CONFIGURED");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(properties.getApiKey()); headers.setContentType(MediaType.APPLICATION_JSON);
            if (async) { headers.set("X-DashScope-Async", "enable"); }
            String payload = body == null ? null : PROVIDER_JSON.writeValueAsString(body);
            ResponseEntity<String> response = http.exchange(URI.create(properties.baseUrl() + path), method,
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode root = PROVIDER_JSON.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (root.hasNonNull("code")) { throw new OutPaintingProviderException(safeCode(root.path("code").asText())); }
            JsonNode output = root.path("output");
            String id = output.path("task_id").asText("");
            String status = output.path("task_status").asText("");
            if (!id.matches("[A-Za-z0-9_-]{1,128}")
                    || !java.util.Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN").contains(status)) {
                throw new OutPaintingProviderException("INVALID_PROVIDER_RESPONSE");
            }
            String resultUrl = output.path("output_image_url").asText(null);
            if ("SUCCEEDED".equals(status) && !validResultUrl(resultUrl)) {
                throw new OutPaintingProviderException("INVALID_PROVIDER_RESULT");
            }
            return new ProviderTask(id, status, resultUrl,
                    output.hasNonNull("code") ? safeCode(output.path("code").asText()) : null);
        } catch (OutPaintingProviderException e) { throw e; }
        catch (IllegalStateException e) { throw new OutPaintingProviderException("PROVIDER_NOT_CONFIGURED"); }
        catch (RestClientException e) { throw new OutPaintingProviderException("PROVIDER_HTTP_ERROR"); }
        catch (Exception e) { throw new OutPaintingProviderException("INVALID_PROVIDER_RESPONSE"); }
    }

    private static void add(Map<String, Object> target, String key, Object value) {
        if (value != null) { target.put(key, value); }
    }

    static boolean validResultUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 4096) { return false; }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_.-]{1,128}") ? code : "PROVIDER_ERROR";
    }
}
