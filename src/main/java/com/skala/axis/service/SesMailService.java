package com.skala.axis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import jakarta.annotation.PreDestroy;
import java.util.List;

/**
 * AWS SES V2 SDK 를 통한 이메일 발송.
 *
 * <p>인증: IRSA (IAM Roles for Service Accounts) — Pod 의 {@code ses-mailer-sa} 가
 * IAM role assume → SES 발송 권한 자동. SMTP credentials 불필요.</p>
 *
 * <p>매니저 셋업: {@code noreply@skala-ai.com} verified domain (DKIM/SPF/DMARC).
 * 자세한 흐름: {@code axis-infra/docs/SES_INTEGRATION.md}.</p>
 */
@Slf4j
@Service
public class SesMailService {

    private final SesV2Client sesClient;
    private final String fromEmail;

    public SesMailService(
            @Value("${aws.region:ap-northeast-2}") String region,
            @Value("${mail.from:noreply@skala-ai.com}") String fromEmail
    ) {
        this.sesClient = SesV2Client.builder()
                .region(Region.of(region))
                .build();
        this.fromEmail = fromEmail;
    }

    /**
     * 단일 수신자 텍스트 발송 (교수님 가이드 기본 형태 — 테스트/단순 알림용).
     */
    public void sendTextMail(String to, String subject, String bodyText) {
        sendBriefing(List.of(to), subject, null, bodyText);
    }

    /**
     * 일일 브리핑 발송 — 다중 수신자 + HTML/Text 둘 다.
     * html 또는 text 둘 중 하나 이상 필수. 둘 다 있으면 multipart.
     */
    public void sendBriefing(List<String> recipients, String subject, String html, String text) {
        if (recipients == null || recipients.isEmpty()) {
            log.warn("SES sendBriefing 스킵 — 수신자 목록 비어있음 (subject={})", subject);
            return;
        }
        if ((html == null || html.isBlank()) && (text == null || text.isBlank())) {
            log.warn("SES sendBriefing 스킵 — html/text 모두 비어있음 (subject={})", subject);
            return;
        }

        Body.Builder bodyBuilder = Body.builder();
        if (html != null && !html.isBlank()) {
            bodyBuilder.html(Content.builder().data(html).charset("UTF-8").build());
        }
        if (text != null && !text.isBlank()) {
            bodyBuilder.text(Content.builder().data(text).charset("UTF-8").build());
        }

        Message message = Message.builder()
                .subject(Content.builder().data(subject).charset("UTF-8").build())
                .body(bodyBuilder.build())
                .build();

        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(Destination.builder().toAddresses(recipients).build())
                .content(EmailContent.builder().simple(message).build())
                .build();

        try {
            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("SES 발송 성공 messageId={} recipients={} subject={}",
                    response.messageId(), recipients.size(), subject);
        } catch (Exception e) {
            log.error("SES 발송 실패 — subject={} recipients={} error={}",
                    subject, recipients.size(), e.getMessage(), e);
            throw e;
        }
    }

    @PreDestroy
    void close() {
        if (sesClient != null) {
            sesClient.close();
        }
    }
}
