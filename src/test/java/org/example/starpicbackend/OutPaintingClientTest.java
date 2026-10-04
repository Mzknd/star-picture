package org.example.starpicbackend;

import org.example.starpicbackend.api.outpainting.*;
import org.example.starpicbackend.config.OutPaintingProperties;
import org.example.starpicbackend.model.dto.outpainting.OutPaintingParameters;
import org.example.starpicbackend.model.entity.Picture;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Contract verification without any cloud or billable request. */
class OutPaintingClientTest {
    RestTemplate http;
    MockRestServiceServer server;
    OutPaintingProperties properties;
    AliyunOutPaintingClient client;
    @BeforeEach void setup() {
        http = new RestTemplate(); server = MockRestServiceServer.bindTo(http).build();
        properties = new OutPaintingProperties(); properties.setEnabled(true);
        properties.setWorkspaceId("workspace-demo"); properties.setApiKey("test-key");
        client = new AliyunOutPaintingClient(properties, http);
    }

    @Test void creationUsesWorkspaceEndpointAndOfficialSnakeCaseContract() {
        server.expect(requestTo("https://workspace-demo.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/image2image/out-painting"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(header("X-DashScope-Async", "enable"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"model\":\"image-out-painting\",\"input\":{\"image_url\":\"https://example.invalid/source.webp\"},"
                        + "\"parameters\":{\"x_scale\":1.5,\"y_scale\":2.0,\"best_quality\":false,\"limit_image_size\":true}}", true))
                .andRespond(withSuccess("{\"output\":{\"task_id\":\"provider-123\",\"task_status\":\"PENDING\"}}",MediaType.APPLICATION_JSON));
        OutPaintingParameters p = new OutPaintingParameters(); p.setXScale(1.5); p.setYScale(2.0); p.setBestQuality(false);
        assertEquals("provider-123", client.create("https://example.invalid/source.webp", p).getTaskId());
        server.verify();
    }

    @Test void queryReadsOutputImageUrlAndChecksProviderTaskIdentity() {
        server.expect(requestTo("https://workspace-demo.cn-beijing.maas.aliyuncs.com/api/v1/tasks/provider-123"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("{\"output\":{\"task_id\":\"provider-123\",\"task_status\":\"SUCCEEDED\","
                        + "\"output_image_url\":\"https://example.invalid/result.png?signature=test\"}}",MediaType.APPLICATION_JSON));
        assertEquals("https://example.invalid/result.png?signature=test", client.query("provider-123").getOutputImageUrl());
        server.verify();
    }

    @Test void failedTaskReadsNestedErrorAndIgnoresRawProviderMessage() {
        server.expect(anything()).andRespond(withSuccess("{\"output\":{\"task_id\":\"provider-123\",\"task_status\":\"FAILED\","
                + "\"code\":\"InvalidParameter.FileDownload\",\"message\":\"private signed URL and credentials\"}}",MediaType.APPLICATION_JSON));
        assertEquals("InvalidParameter.FileDownload", client.query("provider-123").getErrorCode());
        server.verify();
    }

    @Test void disabledFeatureAndPathInjectionNeverCallProvider() {
        properties.setEnabled(false);
        assertThrows(OutPaintingProviderException.class, () -> client.create("https://example.invalid/source.webp",new OutPaintingParameters()));
        properties.setEnabled(true);
        assertThrows(OutPaintingProviderException.class, () -> client.query("../another-task"));
        server.verify();
    }

    @Test void providerHttpErrorsDoNotExposeBodiesOrSecrets() {
        server.expect(anything()).andRespond(withServerError().body("test-key https://private.example/signature"));
        OutPaintingProviderException failure = assertThrows(OutPaintingProviderException.class, () -> client.query("provider-123"));
        assertEquals("PROVIDER_HTTP_ERROR", failure.getErrorCode());
        assertFalse(failure.getMessage().contains("test-key")); assertFalse(failure.getMessage().contains("signature"));
        server.verify();
    }

    @Test void conflictingModesAndNonFiniteScaleAreRejectedBeforeBilling() {
        Picture source = new Picture(); source.setPicWidth(1024); source.setPicHeight(1024);
        OutPaintingParameters p = new OutPaintingParameters(); p.setXScale(Double.NaN);
        assertThrows(RuntimeException.class, () -> p.validate(source));
        p.setXScale(1.5); p.setOutputRatio("4:3");
        assertThrows(RuntimeException.class, () -> p.validate(source));
    }
}
