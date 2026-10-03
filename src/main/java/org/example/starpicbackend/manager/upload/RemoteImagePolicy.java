package org.example.starpicbackend.manager.upload;

import org.example.starpicbackend.exception.BusinessException;
import org.example.starpicbackend.exception.ErrorCode;
import org.example.starpicbackend.exception.ThrowUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.*;
import java.util.Arrays;

/** HTTP 下载入口校验；生产环境同时使用主机白名单和网络出口限制。 */
@Component
public class RemoteImagePolicy {
    @Value("${picture.upload.allowed-hosts:}") private String allowedHosts;
    public void validate(String url) {
        try {
            URI uri = new URI(url);
            ThrowUtils.throwIf(!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()),
                    ErrorCode.PARAMS_ERROR, "只支持 HTTP/HTTPS 图片地址");
            ThrowUtils.throwIf(uri.getHost() == null || uri.getUserInfo() != null,
                    ErrorCode.PARAMS_ERROR, "图片地址不合法");
            ThrowUtils.throwIf(uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443,
                    ErrorCode.PARAMS_ERROR, "图片地址端口不受支持");
            if (allowedHosts != null && !allowedHosts.isBlank()) {
                boolean allowed = Arrays.stream(allowedHosts.split(","))
                        .map(String::trim).anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
                ThrowUtils.throwIf(!allowed, ErrorCode.PARAMS_ERROR, "图片来源不在允许的主机列表中");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                ThrowUtils.throwIf(isPrivate(address), ErrorCode.PARAMS_ERROR, "禁止访问内网或保留地址");
            }
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址无法解析"); }
    }
    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) { return true; }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 255, second = b[1] & 255;
            return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 192 && second == 0);
        }
        return (b[0] & 0xfe) == 0xfc || (b[0] == 0 && b[1] == 0);
    }
}
