package cn.hnust.selection.service.impl;

import cn.hnust.selection.service.EmailDelivery;
import cn.hnust.selection.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailDelivery implements EmailDelivery {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String username;
    private final String password;

    public SmtpEmailDelivery(@Value("${app.email.host:smtp.qq.com}") String host,
                            @Value("${app.email.port:465}") int port,
                            @Value("${app.email.username:}") String username,
                            @Value("${app.email.password:}") String password) {
        this.username = username;
        this.password = password;
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        sender.getJavaMailProperties().setProperty("mail.smtp.ssl.enable", "true");
        sender.getJavaMailProperties().setProperty("mail.smtp.ssl.checkserveridentity", "true");
        sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
        sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
        sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "5000");
    }

    @Override public boolean isConfigured() {
        return !username.trim().isEmpty() && !password.trim().isEmpty();
    }

    @Override public void sendCode(String address, String code, boolean binding) {
        if (!isConfigured()) throw new ApiException("EMAIL_UNAVAILABLE", "邮箱服务尚未配置，请联系管理员。", HttpStatus.SERVICE_UNAVAILABLE);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(username);
        message.setTo(address);
        message.setSubject(binding ? "师生互选系统：验证绑定邮箱" : "师生互选系统：找回密码");
        message.setText("您的验证码是：" + code + "\n有效期 5 分钟，仅可使用一次。请勿将验证码提供给他人。\n如果不是您本人操作，请忽略本邮件。");
        try { sender.send(message); }
        catch (MailException exception) {
            throw new ApiException("EMAIL_UNAVAILABLE", "验证码邮件发送失败，请稍后重试或联系管理员。", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
