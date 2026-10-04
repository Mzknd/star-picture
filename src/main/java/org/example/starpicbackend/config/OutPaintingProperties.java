package org.example.starpicbackend.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Default-off settings. Credentials are supplied through deployment environment variables. */
@Data
@Component
public class OutPaintingProperties {
    @Value("${AI_OUT_PAINTING_ENABLED:false}")
    private boolean enabled;
    /** Comma-separated exact user IDs; an empty list gives ordinary users no billable access. */
    @Value("${AI_OUT_PAINTING_ALLOWED_USER_IDS:}")
    private String allowedUserIds = "";
    @Value("${DASHSCOPE_API_KEY:}")
    @ToString.Exclude
    private String apiKey = "";
    @Value("${DASHSCOPE_WORKSPACE_ID:}")
    private String workspaceId = "";
    @Value("${AI_OUT_PAINTING_DAILY_LIMIT:5}")
    private int dailyLimit = 5;
    @Value("${AI_OUT_PAINTING_ACTIVE_LIMIT:2}")
    private int activeLimit = 2;
    @Value("${AI_OUT_PAINTING_POLL_INTERVAL_SECONDS:5}")
    private int pollIntervalSeconds = 5;

    public boolean allowsUser(Long userId) {
        if (userId == null || allowedUserIds == null || allowedUserIds.isBlank()) { return false; }
        String exactId = userId.toString();
        return java.util.Arrays.stream(allowedUserIds.split(","))
                .map(String::trim).anyMatch(exactId::equals);
    }
    public String baseUrl() {
        // Treat the workspace as a DNS label, never an arbitrary endpoint.
        if (workspaceId == null || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9-]{0,62}")) {
            throw new IllegalStateException("DASHSCOPE_WORKSPACE_ID未配置或格式错误");
        }
        return "https://" + workspaceId + ".cn-beijing.maas.aliyuncs.com";
    }
}
