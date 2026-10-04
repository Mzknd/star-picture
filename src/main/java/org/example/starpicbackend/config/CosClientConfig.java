package org.example.starpicbackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    private String host;
    private String secretId;
    private String secretKey;
    private String region;
    private String bucket;

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(5000);
        clientConfig.setConnectionRequestTimeout(5000);
        clientConfig.setSocketTimeout(30000);
        clientConfig.setMaxConnectionsCount(50);
        clientConfig.setMaxErrorRetry(2);
        return new COSClient(credentials, clientConfig);
    }
}