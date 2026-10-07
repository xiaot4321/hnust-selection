package cn.hnust.selection.controller;

import cn.hnust.selection.common.Result;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.request.EmailVerificationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.EmailRecoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class EmailRecoveryController {
    private final EmailRecoveryService service;
    private final ConcurrentHashMap<String,Bucket> buckets = new ConcurrentHashMap<>();
    public EmailRecoveryController(EmailRecoveryService service) { this.service=service; }

    @GetMapping("/email")
    public Result<Map<String,Object>> status(@AuthenticationPrincipal AccountPrincipal actor) {
        return Result.success(service.status(actor.getAccountId()));
    }
    @PostMapping("/email/code")
    public Result<Map<String,Object>> bindingCode(@AuthenticationPrincipal AccountPrincipal actor,
        @Valid @RequestBody EmailVerificationRequest request, HttpServletRequest http) {
        limit(http, "send", 10);
        return Result.success(service.bindingCode(actor.getAccountId(), request.getEmail(), request.getCurrentPassword()));
    }
    @PostMapping("/email/confirm")
    public Result<Boolean> confirm(@AuthenticationPrincipal AccountPrincipal actor,
        @Valid @RequestBody EmailVerificationRequest request, HttpServletRequest http) {
        requiredCode(request); limit(http, "verify", 30);
        service.confirmBinding(actor.getAccountId(), request.getChallengeId(), request.getCode());
        return Result.success(true);
    }
    @PostMapping("/recovery/code")
    public Result<Map<String,Object>> recoveryCode(@Valid @RequestBody EmailVerificationRequest request, HttpServletRequest http) {
        requiredLogin(request); limit(http, "send", 10);
        return Result.success(service.recoveryCode(request.getLoginIdentifier(), request.getEmail()));
    }
    @PostMapping("/recovery/reset")
    public Result<Boolean> reset(@Valid @RequestBody EmailVerificationRequest request, HttpServletRequest http) {
        requiredLogin(request); requiredCode(request); limit(http, "verify", 30);
        service.reset(request.getLoginIdentifier(), request.getEmail(), request.getChallengeId(), request.getCode(), request.getNewPassword());
        return Result.success(true);
    }
    private void requiredLogin(EmailVerificationRequest request) {
        if (request.getLoginIdentifier()==null || request.getLoginIdentifier().trim().isEmpty()) throw badRequest();
    }
    private void requiredCode(EmailVerificationRequest request) {
        if (request.getChallengeId()==null || !request.getChallengeId().matches("[0-9a-f]{64}")
            || request.getCode()==null || !request.getCode().matches("[0-9]{6}")) throw badRequest();
    }
    private ApiException badRequest() { return new ApiException("INVALID_ARGUMENT", "请填写账号、邮箱及六位验证码。", HttpStatus.BAD_REQUEST); }
    private static class Bucket { long expires; int count; Bucket(long expires) { this.expires=expires; } }
    private synchronized void limit(HttpServletRequest request, String kind, int maximum) {
        long now=System.currentTimeMillis();
        buckets.entrySet().removeIf(entry -> entry.getValue().expires<=now);
        String key=kind+":"+request.getRemoteAddr(); // Do not trust client-supplied forwarding headers.
        Bucket bucket=buckets.get(key);
        if (bucket==null) {
            if (buckets.size()>=10000) throw limited();
            bucket=new Bucket(now+3600000); buckets.put(key,bucket);
        }
        if (++bucket.count>maximum) throw limited();
    }
    private ApiException limited() { return new ApiException("RATE_LIMITED", "操作过于频繁，请稍后重试。", HttpStatus.TOO_MANY_REQUESTS); }
}
