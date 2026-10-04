package org.example.starpicbackend;

import com.sun.net.httpserver.HttpServer;
import org.example.starpicbackend.manager.upload.*;
import org.example.starpicbackend.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.io.File;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteImageUploadTest {
    static class Upload extends UrlPictureUpload {
        void check(String url) { validPicture(url); }
        void download(String url,File file) throws Exception { processFile(url,file); }
    }
    @Test void blocksLocalPrivateAndNonHttpAddresses() {
        RemoteImagePolicy policy=new RemoteImagePolicy();
        for(String url:new String[]{"file:///etc/passwd","http://127.0.0.1/image","http://10.0.0.1/a","http://169.254.169.254/a","http://[::1]/a"}) {
            assertThrows(BusinessException.class,()->policy.validate(url));
        }
    }
    @Test void productionUrlUploadRequiresConfiguredAllowlist() {
        RemoteImagePolicy policy=new RemoteImagePolicy();
        ReflectionTestUtils.setField(policy,"requireAllowlist",true);
        assertThrows(BusinessException.class,()->policy.validate("https://example.invalid/image.png"));
    }
    @Test void rejectsHostOutsideConfiguredAllowlistBeforeDnsLookup() {
        RemoteImagePolicy policy=new RemoteImagePolicy();
        ReflectionTestUtils.setField(policy,"allowedHosts","trusted.example.invalid");
        assertThrows(BusinessException.class,()->policy.validate("https://untrusted.example.invalid/image.png"));
    }
    @Test void headRejectsOversizeBeforeDownloadingBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger downloads=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/image",exchange->{exchange.getResponseHeaders().set("Content-Type","image/png");
            exchange.getResponseHeaders().set("Content-Length","9999");
            if("GET".equals(exchange.getRequestMethod())) {downloads.incrementAndGet();}
            exchange.sendResponseHeaders(200,-1); exchange.close();});
        server.start();
        try { Upload upload=new Upload(); ReflectionTestUtils.setField(upload,"policy",mock(RemoteImagePolicy.class));
            ReflectionTestUtils.setField(upload,"maxSizeBytes",10L);
            assertThrows(BusinessException.class,()->upload.check("http://127.0.0.1:"+server.getAddress().getPort()+"/image"));
            assertEquals(0,downloads.get());
        } finally {server.stop(0);}
    }
    @Test void streamingLimitRejectsChunkedResponseWithoutContentLength() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/image",exchange->{exchange.getResponseHeaders().set("Content-Type","image/png");
            exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write(new byte[128]); exchange.close();});
        server.start(); File file=File.createTempFile("star-upload-test-",".tmp");
        try { Upload upload=new Upload(); ReflectionTestUtils.setField(upload,"policy",mock(RemoteImagePolicy.class));
            ReflectionTestUtils.setField(upload,"maxSizeBytes",10L);
            assertThrows(BusinessException.class,()->upload.download("http://127.0.0.1:"+server.getAddress().getPort()+"/image",file));
        } finally {server.stop(0); Files.deleteIfExists(file.toPath());}
    }
}
