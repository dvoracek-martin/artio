package io.artio.api.security;

import io.artio.users.Email;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Optional OIDC login handler. It requires an identity-provider-asserted verified email. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {
    private final JdbcTemplate jdbc;private final JwtEncoder encoder;private final String storefrontUrl;private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public OAuth2LoginSuccessHandler(JdbcTemplate jdbc,JwtEncoder encoder,PlatformTransactionManager transactionManager,@Value("${artio.storefront-url:http://localhost:4200}") String storefrontUrl){this.jdbc=jdbc;this.encoder=encoder;this.storefrontUrl=storefrontUrl;this.transactions=new TransactionTemplate(transactionManager);}

    @Override public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication)throws IOException,ServletException{
        try{
            if(!(authentication instanceof OAuth2AuthenticationToken oauth)||!(oauth.getPrincipal() instanceof OidcUser oidc))throw new AuthenticationServiceException("Only verified OpenID Connect identities are supported");
            String provider=oauth.getAuthorizedClientRegistrationId();String subject=oidc.getSubject();String email;
            try{if(!Boolean.TRUE.equals(oidc.getEmailVerified())||oidc.getEmail()==null)throw new IllegalArgumentException("The identity provider must return a verified email");email=new Email(oidc.getEmail()).value();}
            catch(IllegalArgumentException invalid){throw new AuthenticationServiceException("A verified email address is required for OIDC login",invalid);}
            UUID userId=transactions.execute(status->resolveOrProvision(provider,subject,email,oidc));
            UserIdentity user=jdbc.queryForObject("SELECT id,email,role FROM users WHERE id=? AND active",(rs,n)->new UserIdentity(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),userId);
            if(java.util.List.of("ADMIN","SUPERADMIN").contains(user.role()))throw new AuthenticationServiceException("Privileged accounts must use Artio password and authenticator sign-in");
            Instant now=Instant.now();jdbc.update("UPDATE users SET last_login=? WHERE id=?",java.sql.Timestamp.from(now),user.id());
            String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().issuer("artio").issuedAt(now).expiresAt(now.plusSeconds(3600)).subject(user.id().toString()).claim("email",user.email()).claim("role",user.role()).build())).getTokenValue();
            response.sendRedirect(storefrontUrl.replaceAll("/$","")+"/shop#access_token="+URLEncoder.encode(token,StandardCharsets.UTF_8));
        }catch(RuntimeException failure){response.sendRedirect(storefrontUrl.replaceAll("/$","")+"/login?oauth_error=verified_identity_required");}
    }

    private UUID resolveOrProvision(String provider,String subject,String email,OidcUser oidc){
        var linked=jdbc.query("SELECT user_id FROM oauth_identities WHERE provider=? AND subject=?",(rs,n)->rs.getObject(1,UUID.class),provider,subject);
        if(!linked.isEmpty())return linked.getFirst();
        var existing=jdbc.query("SELECT id,email_verified,active FROM users WHERE email=? FOR UPDATE",(rs,n)->new ExistingUser(rs.getObject(1,UUID.class),rs.getBoolean(2),rs.getBoolean(3)),email);
        UUID userId;
        if(existing.isEmpty()){
            userId=UUID.randomUUID();String first=name(oidc.getGivenName(),"Customer"),last=name(oidc.getFamilyName(),"Artio");String randomPassword=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(48));
            jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,email_verified,created_at) VALUES (?,?,?,?,?,'CUSTOMER',TRUE,now())",userId,email,passwords.encode(randomPassword),first,last);
        }else{
            ExistingUser account=existing.getFirst();if(!account.active()||!account.emailVerified())throw new AuthenticationServiceException("This email is linked to an inactive or unverified Artio account");userId=account.id();
        }
        jdbc.update("INSERT INTO oauth_identities(provider,subject,user_id,provider_email) VALUES (?,?,?,?)",provider,subject,userId,email);
        return userId;
    }
    private byte[] randomBytes(int n){byte[] result=new byte[n];new java.security.SecureRandom().nextBytes(result);return result;}
    private static String name(String value,String fallback){if(value==null||value.isBlank())return fallback;return value.substring(0,Math.min(value.length(),120));}
    private record ExistingUser(UUID id,boolean emailVerified,boolean active){}
    private record UserIdentity(UUID id,String email,String role){}
}
