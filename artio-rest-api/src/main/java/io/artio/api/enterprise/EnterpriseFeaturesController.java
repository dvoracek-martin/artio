package io.artio.api.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Commerce extensions for a single independently deployed Artio installation. */
@RestController
public class EnterpriseFeaturesController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom secureRandom=new SecureRandom();
    public EnterpriseFeaturesController(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    // Cookie preferences are versioned snapshots. Essential cookies are always required.
    @GetMapping("/api/privacy/consent")
    public ConsentView consent(JwtAuthenticationToken auth){UUID user=user(auth);var rows=jdbc.query("SELECT analytics,marketing,policy_version,recorded_at FROM consent_records WHERE user_id=? ORDER BY recorded_at DESC LIMIT 1",(rs,n)->new ConsentView(true,rs.getBoolean(1),rs.getBoolean(2),rs.getString(3),rs.getTimestamp(4).toInstant()),user);return rows.isEmpty()?new ConsentView(true,false,false,"2026-10",null):rows.getFirst();}
    @PostMapping("/api/privacy/consent") @Transactional
    public ConsentView saveConsent(@RequestBody ConsentRequest request,JwtAuthenticationToken auth){if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Consent preferences are required");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO consent_records(id,user_id,analytics,marketing,policy_version) VALUES (?,?,?,?,?)",id,user(auth),request.analytics(),request.marketing(),request.policyVersion()==null?"2026-10":request.policyVersion());return consent(auth);}

    @GetMapping("/api/privacy/export")
    public ResponseEntity<byte[]> exportData(JwtAuthenticationToken auth){UUID id=user(auth);
        Map<String,Object> export=new LinkedHashMap<>();
        export.put("exportedAt",Instant.now());
        export.put("account",jdbc.queryForMap("SELECT id,email,first_name,last_name,role,email_verified,created_at,last_login FROM users WHERE id=?",id));
        export.put("orders",jdbc.queryForList("SELECT id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at FROM orders WHERE user_id=? ORDER BY created_at",id));
        export.put("orderItems",jdbc.queryForList("SELECT oi.order_id,oi.variant_id,oi.bundle_id,oi.quantity,oi.unit_price,oi.currency FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.user_id=? ORDER BY o.created_at",id));
        export.put("notifications",jdbc.queryForList("SELECT type,subject,body,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at",id));
        export.put("consents",jdbc.queryForList("SELECT essential,analytics,marketing,policy_version,recorded_at FROM consent_records WHERE user_id=? ORDER BY recorded_at",id));
        export.put("loyalty",jdbc.queryForList("SELECT points,tier,updated_at FROM loyalty_accounts WHERE user_id=?",id));
        try{return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=artio-personal-data.json").body(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export));}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not prepare personal data export",e);}
    }

    @DeleteMapping("/api/privacy/account") @Transactional
    public Map<String,String> eraseAccount(JwtAuthenticationToken auth){UUID id=user(auth);String anonymous="deleted+"+UUID.randomUUID()+"@privacy.artio.invalid";String randomPassword=Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(48));
        jdbc.update("DELETE FROM carts WHERE user_id=?",id);
        jdbc.update("UPDATE orders SET shipping_address='[redacted]',billing_address='[redacted]' WHERE user_id=?",id);
        jdbc.update("UPDATE users SET email=?,password_hash=?,first_name='Deleted',last_name='Customer',active=FALSE,email_verified=FALSE,permissions='' WHERE id=?",anonymous,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(randomPassword),id);
        return Map.of("status","ANONYMIZED","message","Account access is disabled. Legally required order records remain with personal address fields redacted.");}

    // Gift-card codes are returned once and only their SHA-256 digests are stored.
    @PostMapping("/api/admin/gift-cards") @Transactional
    public GiftCardIssued issueGiftCard(@RequestBody GiftCardRequest request){if(request==null||request.amount()==null||request.amount().signum()<=0||request.amount().scale()>4||request.currency()==null||!request.currency().matches("[A-Za-z]{3}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A positive amount and ISO currency are required");String code=randomGiftCode();UUID id=UUID.randomUUID();jdbc.update("INSERT INTO gift_cards(id,code_hash,initial_balance,balance,currency,expires_at) VALUES (?,?,?,?,?,?)",id,sha256(code),request.amount(),request.amount(),request.currency().toUpperCase(Locale.ROOT),request.expiresAt()==null?null:java.sql.Timestamp.from(request.expiresAt()));return new GiftCardIssued(id,code,request.amount(),request.currency().toUpperCase(Locale.ROOT),request.expiresAt());}

    @PostMapping("/api/gift-cards/redeem") @Transactional
    public GiftCardBalance redeem(@RequestBody GiftCardRedeem request,JwtAuthenticationToken auth){if(request==null||request.code()==null||request.amount()==null||request.amount().signum()<=0||request.amount().scale()>4||request.currency()==null||!request.currency().matches("(?i)[a-z]{3}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A gift card code, ISO currency and positive amount are required");String hash=sha256(request.code().replace("-","").toUpperCase(Locale.ROOT));
        var rows=jdbc.query("SELECT id,balance,currency FROM gift_cards WHERE code_hash=? AND active AND (expires_at IS NULL OR expires_at>now()) FOR UPDATE",(rs,n)->new GiftRow(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getString(3).trim()),hash);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Gift card not found, inactive or expired");GiftRow card=rows.getFirst();if(!request.currency().equalsIgnoreCase(card.currency()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Gift card currency does not match");if(request.amount().compareTo(card.balance())>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Gift card balance is insufficient");
        jdbc.update("UPDATE gift_cards SET balance=balance-? WHERE id=?",request.amount(),card.id());jdbc.update("INSERT INTO gift_card_transactions(id,gift_card_id,user_id,order_id,amount,kind) VALUES (?,?,?,?,?,'REDEEM')",UUID.randomUUID(),card.id(),user(auth),request.orderId(),request.amount());return new GiftCardBalance(card.id(),card.balance().subtract(request.amount()),card.currency());}

    @GetMapping("/api/loyalty/me")
    public LoyaltyView loyalty(JwtAuthenticationToken auth){UUID user=user(auth);jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);return jdbc.queryForObject("SELECT points,tier,updated_at FROM loyalty_accounts WHERE user_id=?",(rs,n)->new LoyaltyView(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),user);}
    @PostMapping("/api/loyalty/earn/{orderId}") @Transactional
    public LoyaltyView earn(@PathVariable UUID orderId,JwtAuthenticationToken auth){UUID user=user(auth);var rows=jdbc.query("SELECT total,status FROM orders WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->new OrderTotal(rs.getBigDecimal(1),rs.getString(2)),orderId,user);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");OrderTotal order=rows.getFirst();if(!Set.of("PAID","SHIPPED","DELIVERED").contains(order.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Points are earned after payment");long points=order.total().setScale(0,RoundingMode.DOWN).longValueExact();jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);int inserted=jdbc.update("INSERT INTO loyalty_transactions(id,user_id,order_id,points,kind) VALUES (?,?,?, ?,'EARN') ON CONFLICT DO NOTHING",UUID.randomUUID(),user,orderId,points);if(inserted==1)jdbc.update("UPDATE loyalty_accounts SET points=points+?,tier=CASE WHEN points+?>=10000 THEN 'GOLD' WHEN points+?>=2500 THEN 'SILVER' ELSE 'BRONZE' END,updated_at=now() WHERE user_id=?",points,points,points,user);return loyalty(auth);}
    @PostMapping("/api/loyalty/redeem") @Transactional
    public LoyaltyView redeemPoints(@RequestBody PointsRequest request,JwtAuthenticationToken auth){if(request==null||request.points()<100||request.points()%100!=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Points must be redeemed in increments of 100");UUID user=user(auth);jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);int changed=jdbc.update("UPDATE loyalty_accounts SET points=points-?,updated_at=now() WHERE user_id=? AND points>=?",request.points(),user,request.points());if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient points");jdbc.update("INSERT INTO loyalty_transactions(id,user_id,points,kind) VALUES (?,?,-?,'REDEEM')",UUID.randomUUID(),user,request.points());return loyalty(auth);}

    @PostMapping("/api/interactions")
    public void interaction(@RequestBody InteractionRequest request,org.springframework.security.core.Authentication auth){if(request==null||request.variantId()==null||!Set.of("VIEW","CART").contains(request.kind()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Client interactions may only be VIEW or CART");UUID user=auth instanceof JwtAuthenticationToken jwt?user(jwt):null;jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind) VALUES (?,?,?,?)",UUID.randomUUID(),user,request.variantId(),request.kind());}
    @GetMapping("/api/recommendations")
    public List<Recommendation> recommendations(@RequestParam(defaultValue="10") int limit,org.springframework.security.core.Authentication auth){if(limit<1||limit>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 50");UUID user=auth instanceof JwtAuthenticationToken jwt?user(jwt):null;
        String sql="SELECT i.variant_id,COUNT(*) score FROM product_interactions i WHERE i.kind IN ('CART','PURCHASE') AND i.occurred_at>now()-interval '90 days' AND (?::uuid IS NULL OR i.variant_id NOT IN (SELECT variant_id FROM product_interactions WHERE user_id=? AND kind='PURCHASE')) GROUP BY i.variant_id ORDER BY score DESC,i.variant_id LIMIT ?";
        return jdbc.query(sql,(rs,n)->new Recommendation(rs.getObject(1,UUID.class),rs.getLong(2),"POPULARITY"),user,user,limit);}

    @PostMapping("/api/admin/orders/{orderId}/fraud-assessment") @Transactional
    public FraudAssessment assessFraud(@PathVariable UUID orderId){var rows=jdbc.query("SELECT o.user_id,o.total,o.currency,o.shipping_address,o.created_at,u.created_at user_created FROM orders o JOIN users u ON u.id=o.user_id WHERE o.id=?",(rs,n)->new FraudInput(rs.getObject("user_id",UUID.class),rs.getBigDecimal("total"),rs.getString("currency").trim(),rs.getString("shipping_address"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("user_created").toInstant()),orderId);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");FraudInput input=rows.getFirst();List<String> signals=new ArrayList<>();int score=0;if(input.amount().compareTo(new BigDecimal("2500"))>0){score+=30;signals.add("high_order_value");}Long recent=jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND created_at>=?",Long.class,input.userId(),Instant.now().minus(1,ChronoUnit.HOURS));if(recent!=null&&recent>2){score+=30;signals.add("order_velocity");}long accountAge=java.time.Duration.between(input.userCreated(),input.orderCreated()).getSeconds();if(accountAge>=0&&accountAge<3600){score+=15;signals.add("new_account");}if(input.shipping().toLowerCase(Locale.ROOT).contains("express")){score+=5;signals.add("express_shipping");}score=Math.min(score,100);String decision=score>=70?"HOLD":score>=40?"REVIEW":"ALLOW";try{jdbc.update("INSERT INTO fraud_reviews(id,order_id,score,signals,decision) VALUES (?,?,?,?::jsonb,?) ON CONFLICT(order_id) DO UPDATE SET score=excluded.score,signals=excluded.signals,decision=excluded.decision,created_at=now()",UUID.randomUUID(),orderId,score,mapper.writeValueAsString(signals),decision);}catch(Exception e){throw new IllegalStateException("Could not persist fraud assessment",e);}return new FraudAssessment(orderId,score,decision,signals,"Deterministic rules only; not a trained ML model.");}

    @GetMapping("/api/currency/convert")
    public ConvertedAmount convert(@RequestParam BigDecimal amount,@RequestParam String from,@RequestParam String to){if(amount==null||amount.signum()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"amount must be non-negative");String base=currency(from),quote=currency(to);if(base.equals(quote))return new ConvertedAmount(amount.setScale(2,RoundingMode.HALF_EVEN),quote,BigDecimal.ONE,Instant.now());var rows=jdbc.query("SELECT rate,valid_from FROM exchange_rates WHERE base_currency=? AND quote_currency=? AND valid_from<=now() ORDER BY valid_from DESC LIMIT 1",(rs,n)->new Rate(rs.getBigDecimal(1),rs.getTimestamp(2).toInstant()),base,quote);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No current exchange rate is configured");Rate rate=rows.getFirst();return new ConvertedAmount(amount.multiply(rate.value()).setScale(2,RoundingMode.HALF_EVEN),quote,rate.value(),rate.validFrom());}
    @PostMapping("/api/admin/currency/rates")
    public void putRate(@RequestBody ExchangeRateRequest request){String base=currency(request.baseCurrency()),quote=currency(request.quoteCurrency());if(base.equals(quote)||request.rate()==null||request.rate().signum()<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid exchange rate");jdbc.update("INSERT INTO exchange_rates(base_currency,quote_currency,rate,valid_from,source) VALUES (?,?,?,?,?)",base,quote,request.rate(),request.validFrom()==null?Instant.now():request.validFrom(),request.source()==null?"ADMIN":request.source().substring(0,Math.min(64,request.source().length())));}

    @PostMapping("/api/admin/subscriptions") @Transactional
    public SubscriptionView createSubscription(@RequestBody SubscriptionRequest request,JwtAuthenticationToken auth){if(request==null||request.items()==null||request.items().isEmpty()||request.frequency()==null||!Set.of("WEEKLY","MONTHLY","QUARTERLY").contains(request.frequency())||request.userId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Subscription user, items and a supported frequency are required");UUID targetUser;try{targetUser=UUID.fromString(request.userId());}catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription user");}if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,targetUser)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");UUID id=UUID.randomUUID();Instant next=nextDate(request.frequency(),Instant.now());try{jdbc.update("INSERT INTO subscriptions(id,user_id,frequency,status,items,next_billing_at) VALUES (?,?,?,'ACTIVE',?::jsonb,?)",id,targetUser,request.frequency(),mapper.writeValueAsString(request.items()),next);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription",e);}return new SubscriptionView(id,request.userId(),request.frequency(),"ACTIVE",next,request.items());}
    @GetMapping("/api/subscriptions")
    public List<SubscriptionView> subscriptions(JwtAuthenticationToken auth){return jdbc.query("SELECT id,user_id,frequency,status,next_billing_at,items::text FROM subscriptions WHERE user_id=? ORDER BY created_at DESC",(rs,n)->{try{return new SubscriptionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),mapper.readValue(rs.getString(6),Object.class));}catch(Exception e){throw new IllegalStateException(e);}},user(auth));}
    @PutMapping("/api/subscriptions/{id}/status")
    public SubscriptionView updateSubscription(@PathVariable UUID id,@RequestBody SubscriptionStatusRequest request,JwtAuthenticationToken auth){if(!Set.of("ACTIVE","PAUSED","CANCELLED").contains(request.status()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription status");int changed=jdbc.update("UPDATE subscriptions SET status=? WHERE id=? AND user_id=?",request.status(),id,user(auth));if(changed==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Subscription not found");return subscriptions(auth).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}

    private static UUID user(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    private static String currency(String value){if(value==null||!value.matches("(?i)[a-z]{3}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Currency must be a three-letter ISO code");return value.toUpperCase(Locale.ROOT);}
    private byte[] randomBytes(int n){byte[] b=new byte[n];secureRandom.nextBytes(b);return b;}
    private String randomGiftCode(){byte[] b=randomBytes(16);return Base64.getEncoder().withoutPadding().encodeToString(b).replace('+','A').replace('/','B').toUpperCase(Locale.ROOT);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Instant nextDate(String frequency,Instant now){return switch(frequency){case "WEEKLY"->now.plus(7,ChronoUnit.DAYS);case "MONTHLY"->now.plus(1,ChronoUnit.MONTHS);case "QUARTERLY"->now.plus(3,ChronoUnit.MONTHS);default->throw new IllegalArgumentException("Unsupported billing frequency");};}

    public record ConsentRequest(boolean analytics,boolean marketing,String policyVersion){}
    public record ConsentView(boolean essential,boolean analytics,boolean marketing,String policyVersion,Instant recordedAt){}
    public record GiftCardRequest(BigDecimal amount,String currency,Instant expiresAt){}
    public record GiftCardIssued(UUID id,String code,BigDecimal balance,String currency,Instant expiresAt){}
    public record GiftCardRedeem(String code,BigDecimal amount,String currency,UUID orderId){}
    public record GiftCardBalance(UUID id,BigDecimal balance,String currency){}
    public record LoyaltyView(long points,String tier,Instant updatedAt){}
    public record PointsRequest(long points){}
    public record InteractionRequest(UUID variantId,String kind){}
    public record Recommendation(UUID variantId,long interactionScore,String strategy){}
    public record FraudAssessment(UUID orderId,int score,String decision,List<String> signals,String model){}
    public record ExchangeRateRequest(String baseCurrency,String quoteCurrency,BigDecimal rate,Instant validFrom,String source){}
    public record ConvertedAmount(BigDecimal amount,String currency,BigDecimal appliedRate,Instant rateValidFrom){}
    public record SubscriptionRequest(String userId,String frequency,List<Map<String,Object>> items){}
    public record SubscriptionStatusRequest(String status){}
    public record SubscriptionView(UUID id,String userId,String frequency,String status,Instant nextBillingAt,Object items){}
    private record GiftRow(UUID id,BigDecimal balance,String currency){}
    private record OrderTotal(BigDecimal total,String status){}
    private record FraudInput(UUID userId,BigDecimal amount,String currency,String shipping,Instant orderCreated,Instant userCreated){}
    private record Rate(BigDecimal value,Instant validFrom){}
}
