package dev.rojae.llmgateway.analytics;

import dev.rojae.llmgateway.security.GatewaySecurityProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public mode discovery contains no credentials or tenant data. */
@RestController
public final class AdminPageController {
    private final GatewaySecurityProperties security;
    public AdminPageController(GatewaySecurityProperties security){this.security=security;}
    @GetMapping("/admin/auth/config") public ResponseEntity<Map<String,Object>> config(){
        var result=new LinkedHashMap<String,Object>();result.put("mode",security.getMode());
        result.put("loginUrl",security.isEnterprise()?"/oauth2/authorization/enterprise":null);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
    }
}
