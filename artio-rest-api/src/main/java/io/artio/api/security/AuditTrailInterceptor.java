package io.artio.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Stores metadata for write requests; request bodies and credentials are deliberately excluded. */
@Component
public class AuditTrailInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    public AuditTrailInterceptor(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception ex){
        if(!java.util.Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())) return;
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        UUID actor=auth instanceof JwtAuthenticationToken jwt?UUID.fromString(jwt.getToken().getSubject()):null;
        UUID tenant=TenantContext.current();
        String ip=request.getRemoteAddr();
        String path=request.getRequestURI(); if(path!=null&&path.length()>512)path=path.substring(0,512);
        String agent=request.getHeader("User-Agent"); if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);
        jdbc.update("INSERT INTO audit_logs(id,tenant_id,user_id,action,path,status,ip_address,user_agent) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),tenant,actor,request.getMethod(),path,response.getStatus(),ip,agent);
    }
}
