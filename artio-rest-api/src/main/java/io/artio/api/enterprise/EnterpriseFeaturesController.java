package io.artio.api.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class EnterpriseFeaturesController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final io.artio.api.licensing.LicensingService licensing;
    private final SecureRandom secureRandom=new SecureRandom();
    public EnterpriseFeaturesController(JdbcTemplate jdbc,ObjectMapper mapper,io.artio.api.licensing.LicensingService licensing){this.jdbc=jdbc;this.mapper=mapper;this.licensing=licensing;}

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
        jdbc.update("UPDATE notification_delivery_outbox SET status='DEAD',locked_at=NULL,last_error_class='ACCOUNT_ERASED',subject='',body='' WHERE user_id=? AND status IN ('PENDING','PROCESSING')",id);
        jdbc.update("UPDATE users SET email=?,password_hash=?,first_name='Deleted',last_name='Customer',active=FALSE,email_verified=FALSE,permissions='' WHERE id=?",anonymous,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(randomPassword),id);
        return Map.of("status","ANONYMIZED","message","Account access is disabled. Legally required order records remain with personal address fields redacted.");}

    // Gift-card codes are returned once and only their SHA-256 digests are stored.
    @PostMapping("/api/admin/gift-cards") @Transactional
    public GiftCardIssued issueGiftCard(@RequestBody GiftCardRequest request){
        if(request==null||request.amount()==null||request.amount().signum()<=0||request.currency()==null||!request.currency().matches("(?i)[a-z]{3}")||request.expiresAt()!=null&&!request.expiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A positive amount, ISO currency and future optional expiry are required");
        String currency=request.currency().toUpperCase(Locale.ROOT);int fractionDigits;
        try{fractionDigits=Currency.getInstance(currency).getDefaultFractionDigits();}catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown ISO currency",invalid);}
        if(fractionDigits<0||request.amount().stripTrailingZeros().scale()>fractionDigits)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Amount exceeds the currency's minor-unit precision");
        String code=randomGiftCode();UUID id=UUID.randomUUID();jdbc.update("INSERT INTO gift_cards(id,code_hash,initial_balance,balance,currency,expires_at) VALUES (?,?,?,?,?,?)",id,sha256(code),request.amount(),request.amount(),currency,request.expiresAt()==null?null:java.sql.Timestamp.from(request.expiresAt()));
        return new GiftCardIssued(id,code,request.amount(),currency,request.expiresAt());
    }

    @GetMapping("/api/admin/gift-cards")
    public List<GiftCardAdminView> giftCards(@RequestParam(defaultValue="100") int limit){
        if(limit<1||limit>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 200");
        return jdbc.query("SELECT id,initial_balance,balance,currency,expires_at,active,created_at FROM gift_cards ORDER BY created_at DESC,id LIMIT ?",
                (rs,n)->new GiftCardAdminView(rs.getObject("id",UUID.class),rs.getBigDecimal("initial_balance"),rs.getBigDecimal("balance"),rs.getString("currency").trim(),rs.getTimestamp("expires_at")==null?null:rs.getTimestamp("expires_at").toInstant(),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant()),limit);
    }

    @GetMapping("/api/admin/gift-cards/{id}/transactions")
    public List<GiftCardTransactionView> giftCardTransactions(@PathVariable UUID id,@RequestParam(defaultValue="100") int limit){
        if(limit<1||limit>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 200");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gift_cards WHERE id=?)",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Gift card not found");
        return jdbc.query("SELECT id,order_id,payment_refund_id,amount,kind,created_at FROM gift_card_transactions WHERE gift_card_id=? ORDER BY created_at DESC,id LIMIT ?",
                (rs,n)->new GiftCardTransactionView(rs.getObject("id",UUID.class),rs.getObject("order_id",UUID.class),rs.getObject("payment_refund_id",UUID.class),rs.getBigDecimal("amount"),rs.getString("kind"),rs.getTimestamp("created_at").toInstant()),id,limit);
    }

    @PutMapping("/api/admin/gift-cards/{id}/active") @Transactional
    public GiftCardAdminView setGiftCardActive(@PathVariable UUID id,@RequestBody GiftCardActivation request,JwtAuthenticationToken auth){
        if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An active state is required");
        var existing=jdbc.query("SELECT active FROM gift_cards WHERE id=? FOR UPDATE",(rs,n)->rs.getBoolean(1),id);
        if(existing.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Gift card not found");
        if(existing.getFirst()!=request.active()){
            jdbc.update("UPDATE gift_cards SET active=? WHERE id=?",request.active(),id);
            UUID actor=user(auth);
            jdbc.update("INSERT INTO audit_logs(id,user_id,action,path,status,occurred_at) VALUES (?,?,?,'/api/admin/gift-cards/'||CAST(? AS text)||'/active',200,now())",
                    UUID.randomUUID(),actor,request.active()?"GIFT_CARD_ON":"GIFT_CARD_OFF",id);
        }
        return giftCards(200).stream().filter(card->card.id().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Gift card not found"));
    }

    @PostMapping("/api/gift-cards/redeem") @Transactional
    public GiftCardRedemption redeem(@RequestBody GiftCardRedeem request,JwtAuthenticationToken auth){
        if(request==null||request.code()==null||request.orderId()==null||request.amount()!=null&&request.amount().signum()<=0||request.currency()!=null&&!request.currency().matches("(?i)[a-z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A gift card code and order id are required; an optional amount must be positive");
        var redemption=GiftCardAccounting.redeem(jdbc,request.code(),user(auth),request.orderId(),request.amount());
        if(request.currency()!=null&&!request.currency().equalsIgnoreCase(redemption.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Gift card currency does not match the request");
        return new GiftCardRedemption(redemption.cardId(),redemption.balance(),redemption.currency(),redemption.appliedAmount(),redemption.remainingDue(),redemption.orderStatus());
    }

    @GetMapping("/api/loyalty/me")
    public LoyaltyView loyalty(JwtAuthenticationToken auth){UUID user=user(auth);jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);return jdbc.queryForObject("SELECT points,tier,updated_at FROM loyalty_accounts WHERE user_id=?",(rs,n)->new LoyaltyView(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),user);}
    @PostMapping("/api/loyalty/earn/{orderId}") @Transactional
    public LoyaltyView earn(@PathVariable UUID orderId,JwtAuthenticationToken auth){UUID user=user(auth);var rows=jdbc.query("SELECT total,status,loyalty_eligible FROM orders WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->new OrderLoyalty(rs.getBigDecimal(1),rs.getString(2),rs.getBoolean(3)),orderId,user);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");OrderLoyalty order=rows.getFirst();if(!Set.of("PAID","PARTIALLY_SHIPPED","SHIPPED","DELIVERED").contains(order.status()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Points are earned after payment");if(!order.eligible())throw new io.artio.api.licensing.FeatureNotLicensedException(io.artio.api.licensing.EnterpriseFeature.LOYALTY);BigDecimal refunded=jdbc.queryForObject("SELECT COALESCE(sum(refunded_amount),0) FROM payments WHERE order_id=?",BigDecimal.class,orderId);LoyaltyAccounting.earnForOrder(jdbc,user,orderId,order.total().subtract(refunded));return loyalty(auth);}
    @PostMapping("/api/loyalty/redeem") @Transactional
    public LoyaltyView redeemPoints(@RequestBody PointsRequest request,@RequestHeader("Idempotency-Key") String key,JwtAuthenticationToken auth){if(request==null||request.points()<100||request.points()%100!=0||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Points must be redeemed in increments of 100 with a valid Idempotency-Key");UUID user=user(auth);jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);jdbc.query("SELECT points FROM loyalty_accounts WHERE user_id=? FOR UPDATE",rs->{if(rs.next())return rs.getLong(1);return 0L;},user);var prior=jdbc.query("SELECT points FROM loyalty_transactions WHERE user_id=? AND kind='REDEEM' AND idempotency_key=?",(rs,n)->rs.getLong(1),user,key);if(!prior.isEmpty()){if(prior.getFirst()!=-request.points())throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was already used for a different points redemption");return loyalty(auth);}int changed=jdbc.update("UPDATE loyalty_accounts SET points=points-?,updated_at=now() WHERE user_id=? AND points>=?",request.points(),user,request.points());if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient points");jdbc.update("INSERT INTO loyalty_transactions(id,user_id,points,kind,idempotency_key) VALUES (?,?,-?,'REDEEM',?)",UUID.randomUUID(),user,request.points(),key);return loyalty(auth);}

    @GetMapping("/api/admin/loyalty/{userId}")
    public LoyaltyAccountView adminLoyalty(@PathVariable UUID userId){
        var accounts=jdbc.query("SELECT points,tier,updated_at FROM loyalty_accounts WHERE user_id=?",
                (rs,n)->new LoyaltyView(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),userId);
        if(accounts.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Loyalty account not found");
        List<LoyaltyTransactionView> transactions=jdbc.query("SELECT id,order_id,refund_id,points,kind,reason,created_at FROM loyalty_transactions WHERE user_id=? ORDER BY created_at DESC LIMIT 200",
                (rs,n)->new LoyaltyTransactionView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getTimestamp(7).toInstant()),userId);
        return new LoyaltyAccountView(userId,accounts.getFirst(),transactions);
    }

    @PostMapping("/api/admin/loyalty/{userId}/adjust") @Transactional
    public LoyaltyAccountView adjustLoyalty(@PathVariable UUID userId,@RequestHeader("Idempotency-Key") String key,@RequestBody LoyaltyAdjustment request,JwtAuthenticationToken auth){
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}")||request==null||request.points()==0||request.points() < -1_000_000||request.points()>1_000_000||request.reason()==null||request.reason().isBlank()||request.reason().length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A non-zero adjustment and reason are required");
        jdbc.update("INSERT INTO loyalty_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",userId);
        jdbc.query("SELECT points FROM loyalty_accounts WHERE user_id=? FOR UPDATE",rs->{if(rs.next())return rs.getLong(1);return 0L;},userId);
        var prior=jdbc.query("SELECT points,reason FROM loyalty_transactions WHERE user_id=? AND kind='ADJUST' AND idempotency_key=?",(rs,n)->new LoyaltyPrior(rs.getLong(1),rs.getString(2)),userId,key);
        if(!prior.isEmpty()){if(prior.getFirst().points()!=request.points()||!Objects.equals(prior.getFirst().reason(),request.reason().trim()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key was already used for a different adjustment");return adminLoyalty(userId);}
        licensing.require(io.artio.api.licensing.EnterpriseFeature.LOYALTY);
        jdbc.update("INSERT INTO loyalty_transactions(id,user_id,points,kind,idempotency_key,reason) VALUES (?,?,?,'ADJUST',?,?)",UUID.randomUUID(),userId,request.points(),key,request.reason().trim());
        jdbc.update("UPDATE loyalty_accounts SET points=points+?,tier=CASE WHEN points+?>=10000 THEN 'GOLD' WHEN points+?>=2500 THEN 'SILVER' ELSE 'BRONZE' END,updated_at=now() WHERE user_id=?",
                request.points(),request.points(),request.points(),userId);
        jdbc.update("INSERT INTO audit_logs(id,user_id,action,path,status,occurred_at) VALUES (?,?,'LOYALTY_ADJUST','/api/admin/loyalty/'||CAST(? AS text),200,now())",
                UUID.randomUUID(),user(auth),userId);
        return adminLoyalty(userId);
    }

    @PostMapping("/api/interactions")
    public void interaction(@RequestBody InteractionRequest request,org.springframework.security.core.Authentication auth){if(request==null||request.variantId()==null||!Set.of("VIEW","CART").contains(request.kind()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Client interactions may only be VIEW or CART");UUID user=auth instanceof JwtAuthenticationToken jwt?user(jwt):null;jdbc.update("INSERT INTO product_interactions(id,user_id,variant_id,kind) VALUES (?,?,?,?)",UUID.randomUUID(),user,request.variantId(),request.kind());}
    @GetMapping("/api/recommendations")
    public List<Recommendation> recommendations(@RequestParam(defaultValue="10") int limit,org.springframework.security.core.Authentication auth){if(limit<1||limit>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"limit must be between 1 and 50");UUID user=auth instanceof JwtAuthenticationToken jwt?user(jwt):null;
        String sql="SELECT i.variant_id,COUNT(*) score FROM product_interactions i WHERE i.kind IN ('CART','PURCHASE') AND i.occurred_at>now()-interval '90 days' AND (?::uuid IS NULL OR i.variant_id NOT IN (SELECT variant_id FROM product_interactions WHERE user_id=? AND kind='PURCHASE')) GROUP BY i.variant_id ORDER BY score DESC,i.variant_id LIMIT ?";
        return jdbc.query(sql,(rs,n)->new Recommendation(rs.getObject(1,UUID.class),rs.getLong(2),"POPULARITY"),user,user,limit);}

    @PostMapping("/api/admin/orders/{orderId}/fraud-assessment") @Transactional
    public FraudAssessment assessFraud(@PathVariable UUID orderId){var rows=jdbc.query("SELECT o.user_id,o.total,o.currency,o.shipping_address,o.created_at,u.created_at user_created FROM orders o JOIN users u ON u.id=o.user_id WHERE o.id=?",(rs,n)->new FraudInput(rs.getObject("user_id",UUID.class),rs.getBigDecimal("total"),rs.getString("currency").trim(),rs.getString("shipping_address"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("user_created").toInstant()),orderId);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");FraudInput input=rows.getFirst();List<String> signals=new ArrayList<>();int score=0;if(input.amount().compareTo(new BigDecimal("2500"))>0){score+=30;signals.add("high_order_value");}Long recent=jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND created_at>=?",Long.class,input.userId(),java.sql.Timestamp.from(Instant.now().minus(1,ChronoUnit.HOURS)));if(recent!=null&&recent>2){score+=30;signals.add("order_velocity");}long accountAge=java.time.Duration.between(input.userCreated(),input.orderCreated()).getSeconds();if(accountAge>=0&&accountAge<3600){score+=15;signals.add("new_account");}if(input.shipping().toLowerCase(Locale.ROOT).contains("express")){score+=5;signals.add("express_shipping");}score=Math.min(score,100);String decision=score>=70?"HOLD":score>=40?"REVIEW":"ALLOW";try{jdbc.update("INSERT INTO fraud_reviews(id,order_id,score,signals,decision) VALUES (?,?,?,?::jsonb,?) ON CONFLICT(order_id) DO UPDATE SET score=excluded.score,signals=excluded.signals,decision=excluded.decision,created_at=now()",UUID.randomUUID(),orderId,score,mapper.writeValueAsString(signals),decision);}catch(Exception e){throw new IllegalStateException("Could not persist fraud assessment",e);}return new FraudAssessment(orderId,score,decision,signals,"Deterministic rules only; not a trained ML model.");}

    @GetMapping("/api/currency/convert")
    public ConvertedAmount convert(@RequestParam BigDecimal amount,@RequestParam String from,@RequestParam String to){if(amount==null||amount.signum()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"amount must be non-negative");String base=currency(from),quote=currency(to);if(base.equals(quote))return new ConvertedAmount(amount.setScale(Currency.getInstance(quote).getDefaultFractionDigits(),RoundingMode.HALF_EVEN),quote,BigDecimal.ONE,Instant.now());var rows=jdbc.query("SELECT rate,valid_from FROM exchange_rates WHERE base_currency=? AND quote_currency=? AND valid_from<=now() ORDER BY valid_from DESC LIMIT 1",(rs,n)->new Rate(rs.getBigDecimal(1),rs.getTimestamp(2).toInstant()),base,quote);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No current exchange rate is configured");Rate rate=rows.getFirst();return new ConvertedAmount(amount.multiply(rate.value()).setScale(Currency.getInstance(quote).getDefaultFractionDigits(),RoundingMode.HALF_EVEN),quote,rate.value(),rate.validFrom());}
    @PostMapping("/api/admin/currency/rates")
    public void putRate(@RequestBody ExchangeRateRequest request){String base=currency(request.baseCurrency()),quote=currency(request.quoteCurrency());if(base.equals(quote)||request.rate()==null||request.rate().signum()<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid exchange rate");jdbc.update("INSERT INTO exchange_rates(base_currency,quote_currency,rate,valid_from,source) VALUES (?,?,?,?,?)",base,quote,request.rate(),java.sql.Timestamp.from(request.validFrom()==null?Instant.now():request.validFrom()),request.source()==null?"ADMIN":request.source().substring(0,Math.min(64,request.source().length())));}

    @PostMapping("/api/admin/subscriptions") @Transactional
    public SubscriptionView createSubscription(@RequestBody SubscriptionRequest request,JwtAuthenticationToken auth){if(request==null||request.items()==null||request.items().isEmpty()||request.frequency()==null||!Set.of("WEEKLY","MONTHLY","QUARTERLY").contains(request.frequency())||request.userId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Subscription user, items and a supported frequency are required");licensing.require(io.artio.api.licensing.EnterpriseFeature.SUBSCRIPTIONS);UUID targetUser;try{targetUser=UUID.fromString(request.userId());}catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription user");}if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,targetUser)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");UUID id=UUID.randomUUID();Instant next=nextDate(request.frequency(),Instant.now());try{jdbc.update("INSERT INTO subscriptions(id,user_id,frequency,status,items,next_billing_at) VALUES (?,?,?,'ACTIVE',?::jsonb,?)",id,targetUser,request.frequency(),mapper.writeValueAsString(request.items()),java.sql.Timestamp.from(next));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription",e);}return new SubscriptionView(id,request.userId(),request.frequency(),"ACTIVE",next,request.items());}
    @GetMapping("/api/subscriptions")
    public List<SubscriptionView> subscriptions(JwtAuthenticationToken auth){return jdbc.query("SELECT id,user_id,frequency,status,next_billing_at,items::text FROM subscriptions WHERE user_id=? ORDER BY created_at DESC",(rs,n)->{try{return new SubscriptionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),mapper.readValue(rs.getString(6),Object.class));}catch(Exception e){throw new IllegalStateException(e);}},user(auth));}
    @PutMapping("/api/subscriptions/{id}/status") @Transactional
    public SubscriptionView updateSubscription(@PathVariable UUID id,@RequestBody SubscriptionStatusRequest request,JwtAuthenticationToken auth){if(request==null||request.status()==null||!Set.of("ACTIVE","PAUSED","CANCELLED").contains(request.status()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid subscription status");var prior=jdbc.query("SELECT status FROM subscriptions WHERE id=? AND user_id=? FOR UPDATE",(rs,n)->rs.getString(1),id,user(auth));if(prior.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Subscription not found");if("ACTIVE".equals(request.status())&&!"ACTIVE".equals(prior.getFirst()))licensing.require(io.artio.api.licensing.EnterpriseFeature.SUBSCRIPTIONS);jdbc.update("UPDATE subscriptions SET status=? WHERE id=? AND user_id=?",request.status(),id,user(auth));return subscriptions(auth).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}

    private static UUID user(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    private static String currency(String value){if(value==null||!value.matches("(?i)[a-z]{3}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Currency must be a three-letter ISO code");String code=value.toUpperCase(Locale.ROOT);try{if(Currency.getInstance(code).getDefaultFractionDigits()<0)throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Currency must have a supported ISO minor-unit policy");}return code;}
    private byte[] randomBytes(int n){byte[] b=new byte[n];secureRandom.nextBytes(b);return b;}
    private String randomGiftCode(){byte[] b=randomBytes(16);return Base64.getEncoder().withoutPadding().encodeToString(b).replace('+','A').replace('/','B').toUpperCase(Locale.ROOT);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Instant nextDate(String frequency,Instant now){String timezone=jdbc.query("SELECT timezone FROM artio_installation_settings WHERE singleton_id=true",(rs,n)->rs.getString(1)).stream().findFirst().orElse("Europe/Zurich");return SubscriptionSchedule.next(frequency,now,java.time.ZoneId.of(timezone));}

    public record ConsentRequest(boolean analytics,boolean marketing,String policyVersion){}
    public record ConsentView(boolean essential,boolean analytics,boolean marketing,String policyVersion,Instant recordedAt){}
    public record GiftCardRequest(BigDecimal amount,String currency,Instant expiresAt){}
    public record GiftCardIssued(UUID id,String code,BigDecimal balance,String currency,Instant expiresAt){}
    public record GiftCardAdminView(UUID id,BigDecimal initialBalance,BigDecimal balance,String currency,Instant expiresAt,boolean active,Instant createdAt){}
    public record GiftCardTransactionView(UUID id,UUID orderId,UUID paymentRefundId,BigDecimal amount,String kind,Instant createdAt){}
    public record GiftCardActivation(boolean active){}
    public record GiftCardRedeem(String code,BigDecimal amount,String currency,UUID orderId){}
    public record GiftCardBalance(UUID id,BigDecimal balance,String currency){}
    public record GiftCardRedemption(UUID id,BigDecimal balance,String currency,BigDecimal appliedAmount,BigDecimal remainingDue,String orderStatus){}
    public record LoyaltyView(long points,String tier,Instant updatedAt){}
    public record LoyaltyAccountView(UUID userId,LoyaltyView balance,List<LoyaltyTransactionView> transactions){}
    public record LoyaltyTransactionView(UUID id,UUID orderId,UUID refundId,long points,String kind,String reason,Instant createdAt){}
    private record LoyaltyPrior(long points,String reason){}
    public record LoyaltyAdjustment(long points,String reason){}
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
    private record OrderLoyalty(BigDecimal total,String status,boolean eligible){}
    private record FraudInput(UUID userId,BigDecimal amount,String currency,String shipping,Instant orderCreated,Instant userCreated){}
    private record Rate(BigDecimal value,Instant validFrom){}
}
